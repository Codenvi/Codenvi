package app.codenvi.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.codenvi.crypto.KeystoreBox
import app.codenvi.net.moshi
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.Base64
import java.util.UUID

/** Everything secret: tokens and device keys, sealed by the Android Keystore (docs/design.md §10.6). */
@JsonClass(generateAdapter = true)
data class SecureData(
    val installId: String = UUID.randomUUID().toString(),
    val serverHost: String? = null,
    val pin: String? = null,
    val refreshToken: String? = null,
    /** deviceId → (keyVersion → Base64 key). All versions are kept so old messages stay readable. */
    val keys: Map<String, Map<String, String>> = emptyMap(),
    val disclaimerAccepted: Boolean = false,
)

class SecureStore(context: Context) {
    private val file = File(context.filesDir, "secure.bin")
    private val box = KeystoreBox()
    private val adapter = moshi.adapter(SecureData::class.java)
    private val state = MutableStateFlow(load())

    val flow: StateFlow<SecureData> = state
    val data: SecureData get() = state.value
    val isPaired get() = data.refreshToken != null && data.serverHost != null

    @Synchronized
    fun update(change: (SecureData) -> SecureData) {
        val next = change(state.value)
        val tmp = File(file.parentFile, "secure.bin.tmp")
        tmp.writeBytes(box.seal(adapter.toJson(next).toByteArray()))
        tmp.renameTo(file)
        state.value = next
    }

    fun key(deviceId: String, version: Int): ByteArray? =
        data.keys[deviceId.lowercase()]?.get(version.toString())?.let { Base64.getDecoder().decode(it) }

    fun latestKeyVersion(deviceId: String): Int? = data.keys[deviceId.lowercase()]?.keys?.mapNotNull { it.toIntOrNull() }?.maxOrNull()

    fun putKey(deviceId: String, version: Int, key: ByteArray) = update { d ->
        val id = deviceId.lowercase()
        val versions = (d.keys[id] ?: emptyMap()) + (version.toString() to Base64.getEncoder().encodeToString(key))
        d.copy(keys = d.keys + (id to versions))
    }

    fun removeDeviceKeys(deviceId: String) = update { it.copy(keys = it.keys - deviceId.lowercase()) }

    fun clear() = update { SecureData(installId = UUID.randomUUID().toString(), disclaimerAccepted = it.disclaimerAccepted) }

    private fun load(): SecureData = try {
        if (file.exists()) adapter.fromJson(String(box.open(file.readBytes()))) ?: SecureData() else SecureData()
    } catch (e: Exception) {
        // Keystore key lost (e.g. device restore): start fresh, the user pairs again.
        SecureData()
    }
}

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Read-aloud defaults until the user changes them in Settings. */
const val DEFAULT_TTS_SPEED = 1.0f
const val DEFAULT_TTS_VOICE = "en-us-x-iom-local"

data class AppSettings(
    val ttsSpeed: Float = DEFAULT_TTS_SPEED,
    /** "" = the system default voice (chosen on purpose); null is never returned, an unset value reads as [DEFAULT_TTS_VOICE]. */
    val ttsVoice: String? = DEFAULT_TTS_VOICE,
    val speechLanguage: String? = null,
    val lastModel: String = "default",
    val lastEffort: String = "default",
    val multimodeProject: String? = null,
    val multimodeSession: String? = null,
    /** Per session: the highest message seq the user has seen (chat opened) or answered (prompt sent). */
    val seenSeq: Map<String, Long> = emptyMap(),
    /** Phone time when "unseen reply" tracking started; older replies are never counted as unseen. */
    val unseenBaselineMs: Long = 0,
    /** Sound and pop-up notification when a reply arrives (also keeps the connection open in the background). */
    val notifyReplies: Boolean = true,
    /** Offline Whisper model used to transcribe dictation (WhisperModel.id). */
    val whisperModel: String = "base.en",
)

/** Non-secret preferences in DataStore. */
class SettingsStore(private val context: Context) {
    private object Keys {
        val ttsSpeed = floatPreferencesKey("tts_speed")
        val ttsVoice = stringPreferencesKey("tts_voice")
        val speechLanguage = stringPreferencesKey("speech_language")
        val lastModel = stringPreferencesKey("last_model")
        val lastEffort = stringPreferencesKey("last_effort")
        val multimodeProject = stringPreferencesKey("multimode_project")
        val multimodeSession = stringPreferencesKey("multimode_session")
        val seenSeq = stringPreferencesKey("seen_seq")
        val unseenBaselineMs = longPreferencesKey("unseen_baseline_ms")
        val notifyReplies = booleanPreferencesKey("notify_replies")
        val whisperModel = stringPreferencesKey("whisper_model")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            ttsSpeed = p[Keys.ttsSpeed] ?: DEFAULT_TTS_SPEED,
            ttsVoice = p[Keys.ttsVoice] ?: DEFAULT_TTS_VOICE,
            speechLanguage = p[Keys.speechLanguage],
            lastModel = p[Keys.lastModel] ?: "default",
            lastEffort = p[Keys.lastEffort] ?: "default",
            multimodeProject = p[Keys.multimodeProject],
            multimodeSession = p[Keys.multimodeSession],
            seenSeq = parseSeen(p[Keys.seenSeq]),
            unseenBaselineMs = p[Keys.unseenBaselineMs] ?: 0L,
            notifyReplies = p[Keys.notifyReplies] ?: true,
            whisperModel = p[Keys.whisperModel] ?: "base.en",
        )
    }

    suspend fun setWhisperModel(id: String) = context.dataStore.edit { it[Keys.whisperModel] = id }
    suspend fun setNotifyReplies(v: Boolean) = context.dataStore.edit { it[Keys.notifyReplies] = v }
    suspend fun setTtsSpeed(v: Float) = context.dataStore.edit { it[Keys.ttsSpeed] = v }
    /** Stores "" for "System default" so it is not mistaken for "never chosen" (which means [DEFAULT_TTS_VOICE]). */
    suspend fun setTtsVoice(v: String) = context.dataStore.edit { it[Keys.ttsVoice] = v }
    suspend fun setSpeechLanguage(v: String?) = context.dataStore.edit { if (v.isNullOrBlank()) it.remove(Keys.speechLanguage) else it[Keys.speechLanguage] = v }
    suspend fun setModel(model: String, effort: String) = context.dataStore.edit { it[Keys.lastModel] = model; it[Keys.lastEffort] = effort }
    suspend fun setMultimodeProject(id: String?) = context.dataStore.edit { if (id == null) it.remove(Keys.multimodeProject) else it[Keys.multimodeProject] = id }
    suspend fun setMultimodeSession(id: String?) = context.dataStore.edit { if (id == null) it.remove(Keys.multimodeSession) else it[Keys.multimodeSession] = id }
    /** Remembers that [sessionId] is seen up to [seq] (never moves backwards). */
    suspend fun markSeen(sessionId: String, seq: Long) = context.dataStore.edit { p ->
        val m = parseSeen(p[Keys.seenSeq]).toMutableMap()
        if ((m[sessionId] ?: -1L) < seq) {
            m[sessionId] = seq
            p[Keys.seenSeq] = m.entries.joinToString(",") { "${it.key}=${it.value}" }
        }
    }
    suspend fun ensureUnseenBaseline() = context.dataStore.edit { p ->
        if (p[Keys.unseenBaselineMs] == null) p[Keys.unseenBaselineMs] = System.currentTimeMillis()
    }
    private fun parseSeen(raw: String?): Map<String, Long> =
        raw?.split(",")?.mapNotNull { e -> e.split("=").takeIf { it.size == 2 }?.let { it[0] to (it[1].toLongOrNull() ?: return@mapNotNull null) } }?.toMap()
            ?: emptyMap()
    suspend fun clear() = context.dataStore.edit { it.clear() }
}
