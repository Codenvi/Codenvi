package app.codenvi.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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

data class AppSettings(
    val ttsSpeed: Float = 1.0f,
    val ttsVoice: String? = null,
    val speechLanguage: String? = null,
    val lastModel: String = "default",
    val lastEffort: String = "default",
    val multimodeProject: String? = null,
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
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            ttsSpeed = p[Keys.ttsSpeed] ?: 1.0f,
            ttsVoice = p[Keys.ttsVoice],
            speechLanguage = p[Keys.speechLanguage],
            lastModel = p[Keys.lastModel] ?: "default",
            lastEffort = p[Keys.lastEffort] ?: "default",
            multimodeProject = p[Keys.multimodeProject],
        )
    }

    suspend fun setTtsSpeed(v: Float) = context.dataStore.edit { it[Keys.ttsSpeed] = v }
    suspend fun setTtsVoice(v: String?) = context.dataStore.edit { if (v == null) it.remove(Keys.ttsVoice) else it[Keys.ttsVoice] = v }
    suspend fun setSpeechLanguage(v: String?) = context.dataStore.edit { if (v.isNullOrBlank()) it.remove(Keys.speechLanguage) else it[Keys.speechLanguage] = v }
    suspend fun setModel(model: String, effort: String) = context.dataStore.edit { it[Keys.lastModel] = model; it[Keys.lastEffort] = effort }
    suspend fun setMultimodeProject(id: String?) = context.dataStore.edit { if (id == null) it.remove(Keys.multimodeProject) else it[Keys.multimodeProject] = id }
    suspend fun clear() = context.dataStore.edit { it.clear() }
}
