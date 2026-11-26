package app.codenvi.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Speaks text with Kokoro (docs/design.md §10.5): one thread turns the text into phoneme chunks and synthesises them, another plays
 * each finished chunk while the next one is being made, so a long reply starts after the first short chunk.
 */
class KokoroSpeaker(private val manager: KokoroManager) {
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var generation = 0
    private var producer: Thread? = null
    private var player: Thread? = null
    @Volatile private var track: AudioTrack? = null

    /**
     * Starts speaking [text]. [onFinished] runs on the main thread when it is done (or stopped by a newer call is NOT reported);
     * [onFailed] runs instead when nothing could be played, so the caller can fall back to another voice.
     */
    fun speak(text: String, model: KokoroModel, voice: String, speed: Float, onStarted: () -> Unit, onFinished: () -> Unit, onFailed: (String) -> Unit) {
        stop()
        val my = ++generation
        val queue = ArrayBlockingQueue<FloatArray>(2)
        val END = FloatArray(0)
        val failure = AtomicReference<String?>(null)
        val played = AtomicBoolean(false)

        producer = Thread {
            try {
                for (ids in manager.chunks(text)) {
                    if (my != generation) return@Thread
                    val audio = manager.synthesize(ids, model, voice, speed)
                    while (my == generation && !queue.offer(audio, 200, TimeUnit.MILLISECONDS)) { /* wait for the player */ }
                }
            } catch (e: Throwable) {
                failure.set(e.message ?: e.javaClass.simpleName)
            } finally {
                while (my == generation && !queue.offer(END, 200, TimeUnit.MILLISECONDS)) { /* wait */ }
            }
        }.also { it.isDaemon = true; it.start() }

        player = Thread {
            var t: AudioTrack? = null
            var frames = 0L
            try {
                while (my == generation) {
                    val chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    if (chunk === END) break
                    if (t == null) {
                        t = newTrack()
                        track = t
                        t.play()
                        played.set(true)
                        main.post { if (my == generation) onStarted() }
                    }
                    var off = 0
                    while (off < chunk.size && my == generation) {
                        val n = t.write(chunk, off, chunk.size - off, AudioTrack.WRITE_BLOCKING)
                        if (n <= 0) break
                        off += n
                        frames += n
                    }
                }
                if (my == generation && t != null) {
                    t.stop() // in stream mode the queued audio still plays to its end
                    var guard = 0
                    while (my == generation && t.playbackHeadPosition < frames && guard++ < 6000) Thread.sleep(50)
                }
            } catch (e: Throwable) {
                failure.compareAndSet(null, e.message ?: e.javaClass.simpleName)
            } finally {
                runCatching { t?.release() }
                if (track === t) track = null
                if (my == generation) {
                    val f = failure.get()
                    main.post {
                        if (my != generation) return@post
                        if (!played.get() && f != null) onFailed(f) else onFinished()
                    }
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    /** Stops speaking at once. Nothing is reported to the callbacks of the speech that was stopped. */
    fun stop() {
        generation++
        runCatching { track?.pause(); track?.flush() }
    }

    private fun newTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(KokoroEngine.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(KokoroEngine.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min * 2, KokoroEngine.SAMPLE_RATE * 4 * 2))
            .build()
    }
}
