package app.codenvi.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

// SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED / _UNAVAILABLE (API 31); literals keep it compiling on older SDK levels.
private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
private const val ERROR_LANGUAGE_UNAVAILABLE = 13
private const val ERROR_SERVER_DISCONNECTED = 11
private const val MAX_EMPTY_CYCLES = 60 // ~ many minutes of pure silence in continuous mode before dictation stops itself
private const val RESTART_DELAY_MS = 150L

/**
 * Speech-to-text with the built-in SpeechRecognizer (docs/design.md §10.4): free, no API key, prefers offline.
 * Partial results stream to [onPartial]; the final text goes to [onFinal]. Never auto-sends.
 *
 * Continuous mode (used by the chat): the recognizer ends by itself after a pause, so it is started again after every
 * result or silence timeout until [stop] is called, and the user can take as long as they like between sentences.
 */
class SpeechInput(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /** Bumped by every start and stop, so a pending quiet retry is dropped when the user has stopped meanwhile. */
    private var session = 0

    /** True from a start until it ends (result, error or stop), including the short gaps while a retry is prepared. */
    @Volatile var active = false
        private set

    private val _recording = MutableStateFlow(false)

    /**
     * True for the whole dictation, including the short gaps while the recognizer restarts in continuous mode
     * (unlike [listening], which follows the recognizer). The record button follows this one, so it does not blink between sentences.
     */
    val recording: StateFlow<Boolean> = _recording

    private fun setActive(value: Boolean) { active = value; _recording.value = value }

    /**
     * Every (re)start of the recognizer plays the system's mic-on / mic-off sounds, so attempts are kept to a minimum:
     * the first one lets the speech service choose offline or online itself; only if it rejects the language is there
     * one more, with the phone's own default language. (Trying "offline only" first made a missing offline pack cost
     * two extra start-stop cycles.)
     */
    fun start(language: String?, onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit, continuous: Boolean = false) {
        session++
        setActive(true)
        emptyCycles = 0
        begin(language, 0, 0, continuous, onPartial, onFinal, onError)
    }

    /** Restarts in a row that heard nothing (continuous mode). It gives up after a long silence so the mic is not left open forever. */
    private var emptyCycles = 0

    private fun begin(language: String?, step: Int, retries: Int = 0, continuous: Boolean = false, onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit) {
        release()
        val mySession = session
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { _listening.value = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { _listening.value = false }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
            }
            override fun onResults(results: Bundle?) {
                _listening.value = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (continuous && mySession == session) {
                    // Keep the microphone open: hand over this sentence, then listen again.
                    if (text.isNullOrBlank()) emptyCycles++ else emptyCycles = 0
                    onFinal(text ?: "")
                    if (emptyCycles <= MAX_EMPTY_CYCLES) {
                        Handler(Looper.getMainLooper()).postDelayed({ if (mySession == session) begin(language, step, 0, true, onPartial, onFinal, onError) }, RESTART_DELAY_MS)
                        return
                    }
                }
                if (mySession == session) setActive(false)
                if (!continuous) onFinal(text ?: "")
            }
            override fun onError(error: Int) {
                _listening.value = false
                if ((error == ERROR_LANGUAGE_NOT_SUPPORTED || error == ERROR_LANGUAGE_UNAVAILABLE)) {
                    val next = if (step == 0 && !language.isNullOrBlank()) 1 else -1
                    if (next >= 0) {
                        // Leave the recognizer callback before tearing it down, then try again with fewer demands.
                        Handler(Looper.getMainLooper()).post { if (mySession == session) begin(language, next, 0, continuous, onPartial, onFinal, onError) }
                        return
                    }
                }
                // Continuous mode: a silence timeout or "no match" is just a pause in the user's talking, so listen again.
                if (continuous && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && ++emptyCycles <= MAX_EMPTY_CYCLES) {
                    Handler(Looper.getMainLooper()).postDelayed({ if (mySession == session) begin(language, step, 0, true, onPartial, onFinal, onError) }, RESTART_DELAY_MS)
                    return
                }
                // Error 11 (service disconnected) or 8 (busy) is typical on the first use, while the speech service is still
                // starting. A fresh attempt a moment later works, so retry quietly instead of showing an error.
                if ((error == ERROR_SERVER_DISCONNECTED || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) && retries < 2) {
                    Handler(Looper.getMainLooper()).postDelayed({ if (mySession == session) begin(language, step, retries + 1, continuous, onPartial, onFinal, onError) }, 500)
                    return
                }
                setActive(false)
                onError(
                    when (error) {
                        ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE ->
                            "This language isn't available for speech recognition. Check the speech language in Settings, or install the language in your phone's voice input settings."
                        SpeechRecognizer.ERROR_AUDIO -> "Couldn't use the microphone."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition is busy. Try again."
                        SpeechRecognizer.ERROR_SERVER, ERROR_SERVER_DISCONNECTED -> "The speech service had a problem. Try again."
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                            "Speech recognition needs a network or an offline language pack."
                        else -> "Speech recognition error ($error)."
                    }
                )
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            if (continuous) {
                // Hints that ask the engine to wait longer before it decides the user has finished (not every engine obeys them).
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 10_000L)
            }
            if (step == 0) putExtra(RecognizerIntent.EXTRA_LANGUAGE, language?.takeIf { it.isNotBlank() } ?: Locale.getDefault().toLanguageTag())
        }
        r.startListening(intent)
    }

    fun stop() {
        session++
        setActive(false)
        release()
    }

    private fun release() {
        // cancel() (not stopListening()) ends the session silently; stopListening() would play another mic-off sound.
        recognizer?.run { runCatching { cancel() }; destroy() }
        recognizer = null
        _listening.value = false
    }
}

/**
 * Read aloud with the system TextToSpeech engine (docs/design.md §10.5). Markdown is stripped and code blocks
 * replaced; long text is chunked under getMaxSpeechInputLength(). Only one message plays at a time.
 */
class ReadAloud(context: Context) {
    private var ready = false
    private val _playing = MutableStateFlow<String?>(null)
    val playing: StateFlow<String?> = _playing
    private var speed = 1.0f
    private var voiceName: String? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId?.endsWith(":last") == true) _playing.value = null }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { _playing.value = null }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { _playing.value = null }
        })
    }

    fun configure(speed: Float, voice: String?) {
        this.speed = speed
        this.voiceName = voice
    }

    fun voices(): List<String> = runCatching { tts.voices?.map { it.name }?.sorted() ?: emptyList() }.getOrDefault(emptyList())

    fun toggle(messageId: String, markdown: String) {
        if (_playing.value == messageId) { stop(); return }
        speak(messageId, markdown)
    }

    fun speak(messageId: String, markdown: String) {
        if (!ready) return
        tts.stop()
        tts.setSpeechRate(speed)
        voiceName?.let { name -> tts.voices?.firstOrNull { it.name == name }?.let { tts.voice = it } }
        val chunks = chunk(stripMarkdown(markdown), TextToSpeech.getMaxSpeechInputLength() - 100)
        _playing.value = messageId
        chunks.forEachIndexed { i, c ->
            val id = if (i == chunks.lastIndex) "$messageId:$i:last" else "$messageId:$i"
            tts.speak(c, TextToSpeech.QUEUE_ADD, null, id)
        }
    }

    fun stop() {
        tts.stop()
        _playing.value = null
    }

    fun shutdown() = tts.shutdown()

    companion object {
        fun stripMarkdown(md: String): String = md
            .replace(Regex("```[\\s\\S]*?```"), " code block omitted. ")
            .replace(Regex("`([^`]*)`"), "$1")
            .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("(?m)^#{1,6}\\s*"), "")
            .replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
            .replace(Regex("(?m)^\\s*>\\s?"), "")
            .replace(Regex("[*_~]{1,3}"), "")
            .replace(Regex("\\|"), " ")
            .trim()

        fun chunk(text: String, max: Int): List<String> {
            if (text.length <= max) return listOf(text)
            val out = mutableListOf<String>()
            var rest = text
            while (rest.length > max) {
                var cut = rest.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'), max)
                if (cut < max / 2) cut = rest.lastIndexOf(' ', max)
                if (cut <= 0) cut = max
                out += rest.substring(0, cut + 1).trim()
                rest = rest.substring(cut + 1)
            }
            if (rest.isNotBlank()) out += rest.trim()
            return out
        }
    }
}
