package app.codenvi.speech

import android.content.Context
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

/** The native side (src/main/cpp): whisper.cpp behind three functions. Not available on devices other than 64-bit ARM. */
object WhisperNative {
    /** False when the library could not be loaded (a 32-bit or x86 device); dictation then uses the system recognizer. */
    val loaded: Boolean = try { System.loadLibrary("codenvi_whisper"); true } catch (e: Throwable) { false }

    external fun nativeInit(path: String): Long
    external fun nativeTranscribe(handle: Long, samples: FloatArray, threads: Int): ByteArray
    external fun nativeFree(handle: Long)
}

private const val MODEL_HOST = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

/** English Whisper models in 5-bit quantised form (MIT licensed, from the whisper.cpp model collection). Sizes are exact, for verifying a download. */
enum class WhisperModel(val id: String, val label: String, val file: String, val bytes: Long) {
    BASE("base.en", "Base (English) - fast, 57 MB", "ggml-base.en-q5_1.bin", 59_721_011L),
    SMALL("small.en", "Small (English) - most accurate, 181 MB", "ggml-small.en-q5_1.bin", 190_098_681L);

    val url: String get() = MODEL_HOST + file
    val megabytes: Int get() = ((bytes + 524_288) / 1_048_576).toInt()

    companion object {
        fun fromId(id: String?): WhisperModel = values().firstOrNull { it.id == id } ?: BASE
    }
}

/** Progress of a model download. */
data class WhisperDownload(val model: WhisperModel, val doneBytes: Long, val totalBytes: Long) {
    val percent: Int get() = if (totalBytes <= 0) 0 else (doneBytes * 100 / totalBytes).toInt().coerceIn(0, 100)
}

/** Cleans up Whisper's output. */
object Transcript {
    private val tags = Regex("""\[[A-Za-z_ ]{2,30}]|\((?:silence|music|applause|laughter|noise|inaudible)\)""", RegexOption.IGNORE_CASE)

    /** Removes non-speech tags such as [BLANK_AUDIO] and (silence), collapses whitespace and trims. */
    fun clean(raw: String): String = raw.replace(tags, " ").replace(Regex("\\s+"), " ").trim()
}

/**
 * Offline transcription of a dictation recording with Whisper (docs/design.md §10.4): downloads the chosen model once into the app's
 * private storage, loads it on first use (and frees it again after two idle minutes, because it takes 100-300 MB of memory), and turns
 * 16-bit 16 kHz PCM into text. Long silences are removed first so Whisper does not invent words for them.
 */
class WhisperManager(private val context: Context) {
    private val dir = File(context.filesDir, "whisper")
    private val main = Handler(Looper.getMainLooper())

    private val _download = MutableStateFlow<WhisperDownload?>(null)
    /** Non-null while a model is downloading. */
    val download: StateFlow<WhisperDownload?> = _download

    /** Bumped when a model is installed or removed, so screens re-read [installed]. */
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes

    val available: Boolean get() = WhisperNative.loaded

    fun file(model: WhisperModel) = File(dir, model.file)

    fun installed(model: WhisperModel): Boolean = file(model).let { it.isFile && it.length() == model.bytes }

    /** Downloads [model] (Wi-Fi is wise: 57-181 MB). Throws on failure; a partial file is removed. */
    suspend fun download(model: WhisperModel) = withContext(Dispatchers.IO) {
        if (installed(model)) return@withContext
        if (_download.value != null) throw IllegalStateException("A model is already downloading.")
        dir.mkdirs()
        val part = File(dir, model.file + ".part")
        _download.value = WhisperDownload(model, 0, model.bytes)
        try {
            val conn = (URL(model.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
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
                        if (done - lastReport >= 256 * 1024) { lastReport = done; _download.value = WhisperDownload(model, done, model.bytes) }
                    }
                }
            }
            if (done != model.bytes) throw java.io.IOException("The download was incomplete ($done of ${model.bytes} bytes).")
            file(model).delete()
            if (!part.renameTo(file(model))) throw java.io.IOException("Couldn't save the model file.")
            _changes.value++
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            _download.value = null
        }
    }

    /** Removes a downloaded model to free its space. */
    fun delete(model: WhisperModel) {
        synchronized(lock) { if (loadedModel == model) freeLocked() }
        file(model).delete()
        File(dir, model.file + ".part").delete()
        _changes.value++
    }

    // ------------------------------------------------------------------ transcription
    private val lock = Any()
    private var handle = 0L
    private var loadedModel: WhisperModel? = null
    private val idleFree = Runnable { synchronized(lock) { freeLocked() } }

    private fun freeLocked() {
        if (handle != 0L) WhisperNative.nativeFree(handle)
        handle = 0L
        loadedModel = null
    }

    /**
     * Transcribes a recording (16-bit little-endian mono PCM at 16 kHz). Blocks: call it from a background thread.
     * Returns "" when there is no speech in it.
     */
    fun transcribe(pcm: ByteArray, model: WhisperModel): String {
        val segments = Segmenter.split(pcm)
        if (segments.isEmpty()) return ""
        val samples = speechOnly(pcm, segments)
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val bytes = synchronized(lock) {
            main.removeCallbacks(idleFree)
            if (loadedModel != model) {
                freeLocked()
                if (!installed(model)) throw IllegalStateException("The voice model is not downloaded.")
                handle = WhisperNative.nativeInit(file(model).absolutePath)
                if (handle == 0L) throw IllegalStateException("The voice model could not be loaded.")
                loadedModel = model
            }
            WhisperNative.nativeTranscribe(handle, samples, threads)
        }
        main.postDelayed(idleFree, 120_000)
        return Transcript.clean(String(bytes, Charsets.UTF_8))
    }

    companion object {
        /** The speech segments of [pcm] joined with 0.4 s of silence between them, as floats in -1..1 (at least 1.2 s long, which Whisper needs). */
        fun speechOnly(pcm: ByteArray, segments: List<IntRange>): FloatArray {
            val gap = SAMPLE_RATE * 4 / 10
            var total = 0
            for (r in segments) total += (r.last - r.first + 1) / 2 + gap
            val out = FloatArray(maxOf(total, SAMPLE_RATE * 12 / 10))
            var pos = 0
            for (r in segments) {
                var i = r.first
                while (i + 1 <= r.last) {
                    out[pos++] = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort() / 32768f
                    i += 2
                }
                pos += gap
            }
            return out
        }
    }
}
