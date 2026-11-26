package app.codenvi.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

private const val KOKORO_BASE = "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main/"

/** Kokoro-82M (Apache-2.0, https://huggingface.co/hexgrad/Kokoro-82M) as ONNX files. The byte sizes are exact, for checking a download. */
enum class KokoroModel(val id: String, val label: String, val remoteFile: String, val localFile: String, val bytes: Long) {
    STANDARD("standard", "Standard (8-bit) - fast, 88 MB", "model_quantized.onnx", "kokoro-standard.onnx", 92_361_116L),
    HIGH("high", "High quality (full precision) - slow, 310 MB", "model.onnx", "kokoro-high.onnx", 325_532_232L);

    val url: String get() = KOKORO_BASE + "onnx/" + remoteFile
    val megabytes: Int get() = ((bytes + 524_288) / 1_048_576).toInt()

    companion object {
        fun fromId(id: String?): KokoroModel = values().firstOrNull { it.id == id } ?: STANDARD
    }
}

/** One of Kokoro's voices: a 0.5 MB style file. */
data class KokoroVoice(val id: String, val label: String) {
    val url: String get() = KOKORO_BASE + "voices/$id.bin"
}

object KokoroVoices {
    val all = listOf(
        KokoroVoice("af_heart", "Heart - American, female (best)"),
        KokoroVoice("af_bella", "Bella - American, female"),
        KokoroVoice("af_nicole", "Nicole - American, female (soft)"),
        KokoroVoice("af_sarah", "Sarah - American, female"),
        KokoroVoice("af_sky", "Sky - American, female"),
        KokoroVoice("af_nova", "Nova - American, female"),
        KokoroVoice("am_michael", "Michael - American, male"),
        KokoroVoice("am_adam", "Adam - American, male"),
        KokoroVoice("am_liam", "Liam - American, male"),
        KokoroVoice("am_eric", "Eric - American, male"),
        KokoroVoice("bf_emma", "Emma - British, female"),
        KokoroVoice("bf_isabella", "Isabella - British, female"),
        KokoroVoice("bm_george", "George - British, male"),
        KokoroVoice("bm_lewis", "Lewis - British, male"),
    )
    const val DEFAULT = "af_heart"
    const val VOICE_FILE_BYTES = 522_240L // every voice file except af.bin
    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: all.first()
}

/** Progress of a model or voice download. */
data class KokoroDownload(val what: String, val doneBytes: Long, val totalBytes: Long) {
    val percent: Int get() = if (totalBytes <= 0) 0 else (doneBytes * 100 / totalBytes).toInt().coerceIn(0, 100)
}

/**
 * The ONNX Runtime session of one Kokoro model. Input: the phoneme token ids (padded with 0 on both ends), a style vector chosen by
 * the number of tokens from the voice file, and the speed. Output: 24 kHz mono float audio.
 */
class KokoroEngine(modelPath: String) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
        opts.setInterOpNumThreads(1)
        session = env.createSession(modelPath, opts)
    }

    /** [voice] is the whole voice file as floats (510 rows of 256). */
    fun synthesize(ids: LongArray, voice: FloatArray, speed: Float): FloatArray {
        val n = ids.size.coerceAtMost(509)
        val input = LongArray(n + 2)
        System.arraycopy(ids, 0, input, 1, n)
        val style = voice.copyOfRange(n * 256, n * 256 + 256)
        return OnnxTensor.createTensor(env, LongBuffer.wrap(input), longArrayOf(1, input.size.toLong())).use { idsTensor ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(style), longArrayOf(1, 256)).use { styleTensor ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(speed)), longArrayOf(1)).use { speedTensor ->
                    session.run(mapOf("input_ids" to idsTensor, "style" to styleTensor, "speed" to speedTensor)).use { result ->
                        val fb = (result[0] as OnnxTensor).floatBuffer
                        val audio = FloatArray(fb.remaining())
                        fb.get(audio)
                        audio
                    }
                }
            }
        }
    }

    override fun close() { runCatching { session.close() } }

    companion object {
        const val SAMPLE_RATE = 24_000

        fun readVoice(file: File): FloatArray {
            val bytes = file.readBytes()
            val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val out = FloatArray(fb.remaining())
            fb.get(out)
            return out
        }
    }
}

/**
 * The files and the loaded engine of offline reading aloud (docs/design.md §10.5): downloads the model and the voices once into the
 * app's private storage, loads the model on first use, and frees it again after three idle minutes (it takes 150-500 MB of memory).
 */
class KokoroManager(private val context: Context) {
    private val dir = File(context.filesDir, "kokoro")
    private val voiceDir = File(dir, "voices")
    private val main = Handler(Looper.getMainLooper())

    /** Needs a 64-bit ARM phone (the ONNX Runtime library is packaged for arm64-v8a only). */
    val available: Boolean = Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a")

    private val _download = MutableStateFlow<KokoroDownload?>(null)
    val download: StateFlow<KokoroDownload?> = _download
    private val _changes = MutableStateFlow(0)
    /** Bumped when a file is installed or removed, so screens re-read the install state. */
    val changes: StateFlow<Int> = _changes

    fun modelFile(m: KokoroModel) = File(dir, m.localFile)
    fun voiceFile(v: String) = File(voiceDir, "$v.bin")

    fun modelInstalled(m: KokoroModel) = modelFile(m).let { it.isFile && it.length() == m.bytes }
    fun voiceInstalled(v: String) = voiceFile(v).let { it.isFile && it.length() >= 400_000 }

    /** True when reading aloud with Kokoro can start right now. */
    fun ready(model: KokoroModel, voice: String) = available && modelInstalled(model) && voiceInstalled(voice)

    private suspend fun fetch(url: String, dest: File, expected: Long, what: String) = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        _download.value = KokoroDownload(what, 0, expected)
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000; readTimeout = 30_000; instanceFollowRedirects = true
            }
            if (conn.responseCode !in 200..299) throw java.io.IOException("The download server answered ${conn.responseCode}.")
            var done = 0L
            conn.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport >= 256 * 1024) { lastReport = done; _download.value = KokoroDownload(what, done, expected) }
                    }
                }
            }
            if (done < expected - 8192 || done > expected + 8192) throw java.io.IOException("The download was incomplete ($done of $expected bytes).")
            dest.delete()
            if (!part.renameTo(dest)) throw java.io.IOException("Couldn't save the file.")
            _changes.value++
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            _download.value = null
        }
    }

    /** Downloads the model (88 or 310 MB: Wi-Fi is wise). */
    suspend fun downloadModel(m: KokoroModel) {
        if (modelInstalled(m)) return
        if (_download.value != null) throw IllegalStateException("A download is already running.")
        fetch(m.url, modelFile(m), m.bytes, m.id)
        if (!modelInstalled(m)) { modelFile(m).delete(); throw java.io.IOException("The downloaded model has the wrong size.") }
    }

    /** Downloads one voice (0.5 MB). */
    suspend fun downloadVoice(id: String) {
        if (voiceInstalled(id)) return
        if (_download.value != null) throw IllegalStateException("A download is already running.")
        fetch(KokoroVoices.byId(id).url, voiceFile(id), KokoroVoices.VOICE_FILE_BYTES, id)
    }

    fun deleteModel(m: KokoroModel) {
        synchronized(lock) { if (loadedModel == m) freeLocked() }
        modelFile(m).delete(); File(dir, m.localFile + ".part").delete()
        _changes.value++
    }

    // ------------------------------------------------------------------ engine
    private val lock = Any()
    private var engine: KokoroEngine? = null
    private var loadedModel: KokoroModel? = null
    private var phonemizer: Phonemizer? = null
    private val voices = HashMap<String, FloatArray>()
    private val idleFree = Runnable { synchronized(lock) { freeLocked() } }

    private fun freeLocked() {
        engine?.close()
        engine = null
        loadedModel = null
        phonemizer = null
        voices.clear()
    }

    /** Text -> phoneme token chunks (loads the dictionary on first use). Blocking. */
    fun chunks(text: String): List<LongArray> = synchronized(lock) {
        main.removeCallbacks(idleFree)
        val p = phonemizer ?: Phonemizer(
            context.assets.open("tts/lexicon_en_us.tsv").reader(Charsets.UTF_8).use { Phonemizer.loadLexicon(it) },
            context.assets.open("tts/kokoro_vocab.json").reader(Charsets.UTF_8).use { Phonemizer.loadVocab(it.readText()) },
        ).also { phonemizer = it }
        p.chunks(text)
    }

    /** One chunk of audio (24 kHz mono). Blocking; loads the model on first use. */
    fun synthesize(ids: LongArray, model: KokoroModel, voice: String, speed: Float): FloatArray {
        val e: KokoroEngine
        val v: FloatArray
        synchronized(lock) {
            main.removeCallbacks(idleFree)
            if (loadedModel != model) { engine?.close(); engine = null }
            e = engine ?: KokoroEngine(modelFile(model).absolutePath).also { engine = it; loadedModel = model }
            v = voices.getOrPut(voice) { KokoroEngine.readVoice(voiceFile(voice)) }
        }
        // Inference runs outside the lock, so stopping and the idle timer never wait for it.
        val audio = e.synthesize(ids, v, speed)
        main.removeCallbacks(idleFree)
        main.postDelayed(idleFree, 180_000)
        return audio
    }
}
