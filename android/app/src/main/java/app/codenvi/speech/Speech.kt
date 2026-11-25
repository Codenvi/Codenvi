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

/**
 * Speech-to-text with the built-in SpeechRecognizer (docs/design.md §10.4): free, no API key, prefers offline.
 * Partial results stream to [onPartial]; the final text goes to [onFinal]. Never auto-sends.
 */
class SpeechInput(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /** Bumped by every start and stop, so a pending quiet retry is dropped when the user has stopped meanwhile. */
    private var session = 0

    /**
     * Error 12 (language not supported) / 13 (language unavailable) usually means the offline pack for the language is
     * missing. So we retry in steps: offline first, then online, then the phone's own default language.
     */
    fun start(language: String?, onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit) {
        session++
        begin(language, 0, 0, onPartial, onFinal, onError)
    }

    private fun begin(language: String?, step: Int, retries: Int = 0, onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit) {
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
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onFinal) ?: onFinal("")
            }
            override fun onError(error: Int) {
                _listening.value = false
                if ((error == ERROR_LANGUAGE_NOT_SUPPORTED || error == ERROR_LANGUAGE_UNAVAILABLE)) {
                    val next = if (step == 0) 1 else if (step == 1 && !language.isNullOrBlank()) 2 else -1
                    if (next >= 0) {
                        // Leave the recognizer callback before tearing it down, then try again with fewer demands.
                        Handler(Looper.getMainLooper()).post { if (mySession == session) begin(language, next, 0, onPartial, onFinal, onError) }
                        return
                    }
                }
                // Error 11 (service disconnected) or 8 (busy) is typical on the first use, while the speech service is still
                // starting. A fresh attempt a moment later works, so retry quietly instead of showing an error.
                if ((error == ERROR_SERVER_DISCONNECTED || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) && retries < 2) {
                    Handler(Looper.getMainLooper()).postDelayed({ if (mySession == session) begin(language, step, retries + 1, onPartial, onFinal, onError) }, 500)
                    return
                }
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
            if (step == 0) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            if (step < 2) putExtra(RecognizerIntent.EXTRA_LANGUAGE, language?.takeIf { it.isNotBlank() } ?: Locale.getDefault().toLanguageTag())
        }
        r.startListening(intent)
    }

    fun stop() {
        session++
        release()
    }

    private fun release() {
        recognizer?.run { stopListening(); destroy() }
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
