package app.codenvi.speech

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Record first, transcribe after (docs/design.md §10.4): the microphone is recorded continuously until the user taps stop, with
 * no recognizer running and so no restarts, pauses or sounds. Then the recording is cut at its pauses into utterances and each one
 * is given to Android's speech recognizer as an audio file (RecognizerIntent.EXTRA_AUDIO_SOURCE, Android 13+); the texts are joined.
 */
object RecordedSupport {
    /** Feeding audio files to the recognizer exists from Android 13 (API 33). */
    val available: Boolean get() = Build.VERSION.SDK_INT >= 33
}

const val SAMPLE_RATE = 16_000

/** Cuts raw 16-bit mono PCM into speech segments at its pauses. Pure, so it is unit-tested. */
object Segmenter {
    private const val FRAME_MS = 50
    /** A pause shorter than this does not split a segment. */
    private const val JOIN_GAP_MS = 1_200
    private const val PAD_MS = 250
    private const val MIN_SPEECH_MS = 250
    /** Recognizers stop listening after roughly a minute, so longer stretches are cut. */
    private const val MAX_SEGMENT_MS = 45_000

    /** Byte ranges (even offsets, 16-bit samples) of the segments in [pcm]. Empty when no speech was found. */
    fun split(pcm: ByteArray, rate: Int = SAMPLE_RATE): List<IntRange> {
        val frameSamples = rate * FRAME_MS / 1000
        val frameBytes = frameSamples * 2
        val frames = pcm.size / frameBytes
        if (frames == 0) return emptyList()
        val rms = DoubleArray(frames) { f ->
            var sum = 0.0
            val base = f * frameBytes
            for (i in 0 until frameSamples) {
                val s = ((pcm[base + 2 * i + 1].toInt() shl 8) or (pcm[base + 2 * i].toInt() and 0xFF)).toShort().toInt()
                sum += s.toDouble() * s
            }
            sqrt(sum / frameSamples)
        }
        val sorted = rms.sortedArray()
        val p95 = sorted[(frames * 95 / 100).coerceAtMost(frames - 1)]
        val threshold = max(150.0, 0.12 * p95)
        val speech = BooleanArray(frames) { rms[it] > threshold }

        // Runs of speech frames, merging runs separated by a short pause.
        val joinFrames = JOIN_GAP_MS / FRAME_MS
        val runs = ArrayList<IntArray>() // [firstFrame, lastFrame, speechFrames]
        var f = 0
        while (f < frames) {
            if (!speech[f]) { f++; continue }
            var end = f
            var count = 0
            var gap = 0
            var i = f
            while (i < frames && gap <= joinFrames) {
                if (speech[i]) { end = i; gap = 0; count++ } else gap++
                i++
            }
            runs += intArrayOf(f, end, count)
            f = end + 1
        }

        val padFrames = PAD_MS / FRAME_MS
        val maxFrames = MAX_SEGMENT_MS / FRAME_MS
        val out = ArrayList<IntRange>()
        for ((first, last, count) in runs) {
            if (count * FRAME_MS < MIN_SPEECH_MS) continue
            var start = max(0, first - padFrames)
            val stop = min(frames - 1, last + padFrames)
            while (stop - start + 1 > maxFrames) {
                // Cut a long stretch at its quietest frame in the last 10 s before the limit.
                val from = start + maxFrames - 200
                var cut = start + maxFrames
                var best = Double.MAX_VALUE
                for (c in from until start + maxFrames) if (rms[c] < best) { best = rms[c]; cut = c }
                out += (start * frameBytes) until (cut * frameBytes)
                start = cut
            }
            out += (start * frameBytes) until ((stop + 1) * frameBytes)
        }
        return out
    }
}

/** Records the microphone to a raw PCM file until [stop]. */
class SpeechRecorder(private val context: Context) {
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording

    @Volatile private var record: AudioRecord? = null
    private var thread: Thread? = null
    val file: File get() = File(context.cacheDir, "dictation.pcm")

    @SuppressLint("MissingPermission") // asked before the first tap (rememberMicPermission)
    fun start(): Boolean {
        if (_recording.value) return true
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(min, SAMPLE_RATE))
        } catch (e: Exception) { return false }
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
        runCatching { file.delete() }
        record = r
        _recording.value = true
        r.startRecording()
        thread = Thread {
            val buf = ByteArray(8192)
            val limit = 30L * 60 * SAMPLE_RATE * 2 // 30 minutes, so a forgotten recording cannot fill the phone
            var written = 0L
            runCatching {
                FileOutputStream(file).use { out ->
                    while (record === r && written < limit) {
                        val n = r.read(buf, 0, buf.size)
                        if (n > 0) { out.write(buf, 0, n); written += n } else if (n < 0) break
                    }
                }
            }
        }.also { it.isDaemon = true; it.start() }
        return true
    }

    /** Stops recording and returns the recorded PCM bytes (empty if nothing was recorded). */
    fun stop(): ByteArray {
        val r = record
        record = null
        _recording.value = false
        runCatching { r?.stop() }
        thread?.join(1_000)
        thread = null
        runCatching { r?.release() }
        val bytes = runCatching { file.readBytes() }.getOrDefault(ByteArray(0))
        runCatching { file.delete() }
        return bytes
    }

    /** Throws the recording away. */
    fun cancel() { stop() }
}

/** Transcribes PCM segments one after another with the system recognizer, reading each as an audio file (Android 13+). */
class SegmentTranscriber(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var token = 0

    /** [onDone] gets the joined text, or null with [onFailed] called instead when nothing could be transcribed. */
    fun run(pcm: ByteArray, segments: List<IntRange>, language: String?, onDone: (String) -> Unit, onFailed: (String) -> Unit) {
        val myToken = ++token
        val texts = ArrayList<String>()
        var failure: String? = null

        fun finish() {
            if (myToken != token) return
            release()
            val joined = texts.joinToString(" ").trim()
            if (joined.isNotEmpty()) onDone(joined) else onFailed(failure ?: "Didn't catch that.")
        }

        fun next(i: Int, useLanguage: Boolean) {
            if (myToken != token) return
            if (i >= segments.size) { finish(); return }
            val seg = segments[i]
            val seed = File(context.cacheDir, "dictation_seg.pcm")
            runCatching { FileOutputStream(seed).use { it.write(pcm, seg.first, seg.last - seg.first + 1) } }
            val pfd = runCatching { ParcelFileDescriptor.open(seed, ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull()
            if (pfd == null) { failure = "Couldn't read the recording."; finish(); return }
            release()
            val r = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = r
            var settled = false
            val timeout = Runnable { if (!settled) { settled = true; pfd.close(); next(i + 1, useLanguage) } }
            fun settle(block: () -> Unit) {
                if (settled) return
                settled = true
                main.removeCallbacks(timeout)
                runCatching { pfd.close() }
                main.post { if (myToken == token) block() }
            }
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onResults(results: Bundle?) {
                    val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    settle { if (!t.isNullOrBlank()) texts += t.trim(); next(i + 1, useLanguage) }
                }
                override fun onError(error: Int) {
                    settle {
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> next(i + 1, useLanguage)
                            12, 13 -> if (useLanguage) next(i, false) else { failure = "This language isn't available for speech recognition."; finish() }
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                                failure = "Speech recognition needs a network or an offline language pack."; finish()
                            }
                            else -> {
                                // Typically the engine does not accept audio files (error 5 / 3 / 8): no point trying the rest.
                                failure = "This speech engine can't transcribe recordings (error $error). Update the Google app or its speech services."
                                finish()
                            }
                        }
                    }
                }
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                if (useLanguage) putExtra(RecognizerIntent.EXTRA_LANGUAGE, language?.takeIf { it.isNotBlank() } ?: Locale.getDefault().toLanguageTag())
                if (Build.VERSION.SDK_INT >= 33) {
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pfd)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
                }
            }
            main.postDelayed(timeout, 60_000) // never hang on one segment
            r.startListening(intent)
        }
        main.post { next(0, true) }
    }

    fun cancel() { token++; release() }

    private fun release() {
        recognizer?.run { runCatching { cancel() }; destroy() }
        recognizer = null
    }
}
