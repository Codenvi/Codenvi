package app.codenvi.net

import com.squareup.moshi.FromJson
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.ToJson
import java.util.Base64

// REST DTOs (Project.md §7.3). Mirrors shared/Codenvi.Contracts/Api/Dtos.cs. byte[] travels as Base64.

@JsonClass(generateAdapter = true)
data class PairRequest(val code: String, val installId: String, val kind: String, val name: String, val appVersion: String,
                       val appBuild: Int, val osVersion: String?, val deviceModel: String?)

@JsonClass(generateAdapter = true)
data class TokenResponse(val accessToken: String, val accessExpiresAtMs: Long, val refreshToken: String,
                         val refreshExpiresAtMs: Long, val installId: String)

@JsonClass(generateAdapter = true)
data class RefreshRequest(val installId: String, val refreshToken: String)

@JsonClass(generateAdapter = true)
data class InstallDto(val id: String, val kind: String, val name: String, val appVersion: String, val appBuild: Int,
                      val osVersion: String?, val deviceModel: String?, val lastIp: String?, val pairedAtMs: Long,
                      val lastSeenAtMs: Long, val revoked: Boolean, val isSelf: Boolean)

@JsonClass(generateAdapter = true)
data class RenameRequest(val name: String)

@JsonClass(generateAdapter = true)
data class DeviceDto(val id: String, val installId: String, val name: String, val keyVersion: Int, val keyCheck: ByteArray,
                     val online: Boolean, val lastOnlineAtMs: Long?, val createdAtMs: Long)

@JsonClass(generateAdapter = true)
data class ProjectDto(val id: String, val deviceId: String, val encMeta: ByteArray, val defaultSessionId: String?,
                      val sortOrder: Int, val createdAtMs: Long, val updatedAtMs: Long)

@JsonClass(generateAdapter = true)
data class PatchProjectRequest(val encMeta: ByteArray? = null, val sortOrder: Int? = null, val defaultSessionId: String? = null)

@JsonClass(generateAdapter = true)
data class SessionDto(val id: String, val projectId: String, val encMeta: ByteArray, val createdAtMs: Long, val updatedAtMs: Long)

@JsonClass(generateAdapter = true)
data class PutSessionRequest(val projectId: String, val encMeta: ByteArray)

@JsonClass(generateAdapter = true)
data class PatchSessionRequest(val encMeta: ByteArray)

@JsonClass(generateAdapter = true)
data class MessageDto(val id: String, val sessionId: String, val seq: Long, val role: String, val jobId: String?,
                      val ciphertext: ByteArray, val clientCreatedAtMs: Long, val createdAtMs: Long)

@JsonClass(generateAdapter = true)
data class SendPromptRequest(val messageId: String, val ciphertext: ByteArray, val clientCreatedAtMs: Long,
                             val model: String?, val effort: String?)

@JsonClass(generateAdapter = true)
data class SendPromptResponse(val messageId: String, val jobId: String, val seq: Long)

@JsonClass(generateAdapter = true)
data class JobDto(val id: String, val deviceId: String, val projectId: String, val sessionId: String, val promptMessageId: String,
                  val kind: String, val model: String?, val effort: String?, val status: String, val errorCode: String?,
                  val encError: ByteArray?, val queuedAtMs: Long, val startedAtMs: Long?, val finishedAtMs: Long?)

@JsonClass(generateAdapter = true)
data class CommitDto(val id: String, val projectId: String, val jobId: String?, val gitHash: String, val kind: String,
                     val encMeta: ByteArray, val revertedBy: String?, val createdAtMs: Long)

@JsonClass(generateAdapter = true)
data class ControlRequest(val requestId: String, val ciphertext: ByteArray, val clientCreatedAtMs: Long)

@JsonClass(generateAdapter = true)
data class ModelCatalogEntry(val alias: String, val label: String, val efforts: List<String>, val enabled: Boolean, val sortOrder: Int)

// ---------------- WebSocket (Project.md §7.4) ----------------

@JsonClass(generateAdapter = true)
data class Envelope(val type: String, val id: String, val ts: Long, val payload: Any?)

object MessageTypes {
    const val HELLO = "hello"
    const val WELCOME = "welcome"
    const val JOB_STATUS = "job.status"
    const val JOB_PROGRESS = "job.progress"
    const val JOB_RESULT = "job.result"
    const val CONTROL_RESPONSE = "control.response"
    const val DEVICE_PRESENCE = "device.presence"
    const val SYNC_CHANGED = "sync.changed"
    const val PING = "ping"
    const val PONG = "pong"
    const val ERROR = "error"
}

@JsonClass(generateAdapter = true)
data class HelloPayload(val role: String, val deviceId: String? = null)

@JsonClass(generateAdapter = true)
data class JobStatusPayload(val jobId: String, val status: String, val startedAtMs: Long? = null, val finishedAtMs: Long? = null,
                            val errorCode: String? = null, val encError: ByteArray? = null, val sessionId: String? = null,
                            val projectId: String? = null)

@JsonClass(generateAdapter = true)
data class JobProgressPayload(val jobId: String, val sessionId: String, val messageId: String, val ciphertext: ByteArray,
                              val clientCreatedAtMs: Long)

@JsonClass(generateAdapter = true)
data class JobResultPayload(val jobId: String, val sessionId: String, val messageId: String, val ciphertext: ByteArray,
                            val clientCreatedAtMs: Long, val commitHash: String? = null, @Json(name = "final") val isFinal: Boolean = true)

@JsonClass(generateAdapter = true)
data class ControlResponsePayload(val requestId: String, val deviceId: String, val projectId: String, val ciphertext: ByteArray,
                                  val clientCreatedAtMs: Long)

@JsonClass(generateAdapter = true)
data class DevicePresencePayload(val deviceId: String, val online: Boolean)

@JsonClass(generateAdapter = true)
data class SyncChangedPayload(val entity: String, val id: String, val parentId: String? = null)

@JsonClass(generateAdapter = true)
data class ErrorPayload(val code: String, val message: String)

object JobStatuses {
    const val QUEUED = "queued"
    const val DELIVERED = "delivered"
    const val RUNNING = "running"
    const val SUCCEEDED = "succeeded"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
    const val REJECTED_STALE = "rejected_stale"
    const val WAITING_LOGIN = "waiting_login"
    val ACTIVE = setOf(QUEUED, DELIVERED, RUNNING, WAITING_LOGIN)
}

class Base64Adapter {
    @ToJson fun toJson(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    @FromJson fun fromJson(text: String): ByteArray = Base64.getDecoder().decode(text)
}

val moshi: Moshi = Moshi.Builder().add(Base64Adapter()).build()

inline fun <reified T> Any?.convertTo(): T = moshi.adapter(T::class.java).fromJsonValue(this)
    ?: throw IllegalArgumentException("Empty payload")

inline fun <reified T> T.toJsonBytes(): ByteArray = moshi.adapter(T::class.java).toJson(this).toByteArray(Charsets.UTF_8)

inline fun <reified T> ByteArray.parseJson(): T = moshi.adapter(T::class.java).fromJson(String(this, Charsets.UTF_8))
    ?: throw IllegalArgumentException("Empty JSON")
