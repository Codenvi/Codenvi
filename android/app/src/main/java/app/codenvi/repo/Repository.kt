package app.codenvi.repo

import android.os.Build
import app.codenvi.BuildConfig
import app.codenvi.crypto.Aad
import app.codenvi.crypto.CommitMeta
import app.codenvi.crypto.ControlCommands
import app.codenvi.crypto.ControlPlain
import app.codenvi.crypto.ControlResultPlain
import app.codenvi.crypto.Directions
import app.codenvi.crypto.E2E
import app.codenvi.crypto.ErrorPlain
import app.codenvi.crypto.ObjectTypes
import app.codenvi.crypto.ProgressPlain
import app.codenvi.crypto.ProjectMeta
import app.codenvi.crypto.PromptPlain
import app.codenvi.crypto.ResultPlain
import app.codenvi.crypto.SessionMeta
import app.codenvi.data.ConnectionString
import app.codenvi.data.SecureStore
import app.codenvi.data.SettingsStore
import app.codenvi.db.AppDatabase
import app.codenvi.db.CommitEntity
import app.codenvi.db.DeviceEntity
import app.codenvi.db.JobEntity
import app.codenvi.db.MessageEntity
import app.codenvi.db.ProjectEntity
import app.codenvi.db.SessionEntity
import app.codenvi.net.ControlRequest
import app.codenvi.net.ControlResponsePayload
import app.codenvi.net.DevicePresencePayload
import app.codenvi.net.Envelope
import app.codenvi.net.Http
import app.codenvi.net.InstallDto
import app.codenvi.net.JobDto
import app.codenvi.net.JobProgressPayload
import app.codenvi.net.JobResultPayload
import app.codenvi.net.JobStatusPayload
import app.codenvi.net.JobStatuses
import app.codenvi.net.MessageTypes
import app.codenvi.net.ModelCatalogEntry
import app.codenvi.net.PairRequest
import app.codenvi.net.PatchProjectRequest
import app.codenvi.net.PatchSessionRequest
import app.codenvi.net.PutSessionRequest
import app.codenvi.net.RenameRequest
import app.codenvi.net.SendPromptRequest
import app.codenvi.net.SyncChangedPayload
import app.codenvi.net.WsClient
import app.codenvi.net.convertTo
import app.codenvi.net.parseJson
import app.codenvi.net.toJsonBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import retrofit2.HttpException
import retrofit2.Response
import java.security.GeneralSecurityException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// ---------------- UI models ----------------
data class DeviceUi(val id: String, val name: String, val online: Boolean, val keyVersion: Int, val hasKey: Boolean, val hasCurrentKey: Boolean)

enum class ProjectState { IDLE, QUEUED, RUNNING, WAITING_LOGIN }

data class ProjectUi(
    val id: String, val deviceId: String, val deviceName: String, val name: String, val devBranch: String,
    val defaultSessionId: String?, val sortOrder: Int, val state: ProjectState, val deviceOnline: Boolean, val locked: Boolean,
)

data class SessionUi(val id: String, val projectId: String, val name: String, val locked: Boolean)

data class ChatItem(
    val id: String, val role: String, val text: String, val timeMs: Long, val jobId: String?,
    val status: String?, val error: String?, val costUsd: Double? = null, val commitHash: String? = null,
)

data class CommitUi(val id: String, val gitHash: String, val kind: String, val message: String, val files: Int,
                    val insertions: Int, val deletions: Int, val reverted: Boolean, val timeMs: Long)

class UserException(message: String) : Exception(message)

/**
 * Single source of truth for the app: talks to the server, keeps the Room cache, encrypts and decrypts
 * (docs/design.md §5, §7, §10).
 */
class Repository(
    private val store: SecureStore,
    val settings: SettingsStore,
    private val db: AppDatabase,
    val http: Http,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val ws = WsClient(http, store, scope)
    private val dao = db.dao()
    private val pendingControl = ConcurrentHashMap<String, CompletableDeferred<ControlResultPlain>>()

    /** Live progress lines per job (not persisted; the server keeps the last 200 per session). */
    private val _progress = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val progress: StateFlow<Map<String, List<String>>> = _progress

    val connected get() = ws.connected
    val revoked get() = http.revoked
    val upgradeRequired get() = http.upgradeRequired
    val secure get() = store.flow
    val isPaired get() = store.isPaired

    private val _catalog = MutableStateFlow(
        listOf(ModelCatalogEntry("default", "Default (PC setting)", listOf("low", "medium", "high", "xhigh", "max"), true, 0))
    )
    val catalog: StateFlow<List<ModelCatalogEntry>> = _catalog

    init {
        scope.launch { settings.ensureUnseenBaseline() }
        scope.launch { ws.events.collect { runCatching { handle(it) } } }
        scope.launch {
            ws.connected.collect { if (it) runCatching { refreshAll() } }
        }
    }

    // ---------------- Pairing ----------------
    suspend fun pair(text: String, name: String): ConnectionString = withContext(Dispatchers.IO) {
        val cs = ConnectionString.parse(text)
        val host = cs.host ?: throw UserException("This string only carries a key. Pair first with a Connect a phone string.")
        val code = cs.pairingCode ?: throw UserException("Pairing code missing.")
        val installId = store.data.installId
        val tokens = try {
            http.pairingApi(host, cs.pin).pair(
                PairRequest(code, installId, "android", name, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE,
                    "Android ${Build.VERSION.RELEASE}", Build.MODEL)
            )
        } catch (e: HttpException) {
            throw UserException(if (e.code() == 401) "The pairing code is invalid, expired or already used." else "Pairing failed (${e.code()}).")
        }
        store.update { it.copy(serverHost = host, pin = cs.pin, installId = installId) }
        http.acceptTokens(tokens)
        if (cs.carriesKey) store.putKey(cs.deviceId!!, cs.keyVersion!!, cs.key!!)
        ws.restart()
        cs
    }

    /** Links this phone with the server's host name and password (the access key set in the ENV_VARS secret). */
    suspend fun pairWithPassword(host: String, password: String, name: String) = withContext(Dispatchers.IO) {
        val installId = store.data.installId
        val tokens = try {
            http.pairingApi(host, null).pair(
                PairRequest(password.trim(), installId, "android", name, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE,
                    "Android ${Build.VERSION.RELEASE}", Build.MODEL)
            )
        } catch (e: HttpException) {
            throw UserException(
                if (e.code() == 401) "The host name or password is wrong (or linking was locked after too many wrong attempts: see docs/self-hosting.md)."
                else "Linking failed (${e.code()})."
            )
        }
        store.update { it.copy(serverHost = host, pin = null, installId = installId) }
        http.acceptTokens(tokens)
        ws.restart()
    }

    fun acceptDisclaimer() = store.update { it.copy(disclaimerAccepted = true) }

    /** Adds a PC's key (from a codenvi://key string or typed), checked against the server's KCV (docs/design.md §5.2). */
    suspend fun addKey(deviceId: String, keyText: String, version: Int? = null) = withContext(Dispatchers.IO) {
        val key = runCatching {
            if (keyText.trim().startsWith("codenvi://")) ConnectionString.parse(keyText).also {
                if (it.deviceId != null && !it.deviceId.equals(deviceId, true)) throw UserException("That key belongs to a different PC.")
            }.key!! else app.codenvi.crypto.DeviceKey.parse(keyText)
        }.getOrElse { throw UserException(it.message ?: "Invalid key") }
        refreshDevices()
        val device = dao.device(deviceId) ?: throw UserException("Unknown PC.")
        val v = version ?: device.keyVersion
        if (v == device.keyVersion && !E2E.keyCheck(key).contentEquals(device.keyCheck))
            throw UserException("This key doesn't match the PC's current key. Check for typos, or get the key again with Show encryption key.")
        store.putKey(deviceId, v, key)
        refreshProjects(deviceId)
    }

    suspend fun addKeyString(text: String) {
        val cs = runCatching { ConnectionString.parse(text) }.getOrElse { throw UserException(it.message ?: "Invalid string") }
        if (!cs.carriesKey) throw UserException("This string has no key.")
        addKey(cs.deviceId!!, text, cs.keyVersion)
    }

    // ---------------- Crypto helpers ----------------
    private fun keyFor(deviceId: String, wire: ByteArray): ByteArray? =
        runCatching { store.key(deviceId, E2E.peekKeyVersion(wire)) }.getOrNull()

    private inline fun <reified T> decrypt(deviceId: String, aad: Aad, wire: ByteArray?): T? {
        if (wire == null) return null
        val key = keyFor(deviceId, wire) ?: return null
        return try { E2E.decrypt(key, aad, wire).parseJson<T>() } catch (e: GeneralSecurityException) { null } catch (e: Exception) { null }
    }

    private fun currentKey(deviceId: String, keyVersion: Int): ByteArray =
        store.key(deviceId, keyVersion) ?: throw UserException("This phone doesn't have the current key for that PC. Add it on the Devices screen.")

    private suspend fun deviceOf(projectId: String): DeviceEntity {
        val p = dao.project(projectId) ?: throw UserException("Unknown project.")
        return dao.device(p.deviceId) ?: throw UserException("Unknown PC.")
    }

    private fun projectMeta(p: ProjectEntity): ProjectMeta? = decrypt(p.deviceId, Aad.meta(p.deviceId, ObjectTypes.PROJECT_META, p.id), p.encMeta)

    // ---------------- Flows for the UI ----------------
    val devices: Flow<List<DeviceUi>> = combine(dao.devices(), store.flow) { list, _ ->
        list.map {
            DeviceUi(it.id, it.name, it.online, it.keyVersion, hasKey = store.latestKeyVersion(it.id) != null,
                hasCurrentKey = store.key(it.id, it.keyVersion) != null)
        }
    }

    val allProjects: Flow<List<ProjectUi>> = combine(dao.allProjects(), dao.devices(), dao.activeJobs(), store.flow) { projects, devices, jobs, _ ->
        projects.mapNotNull { p -> toUi(p, devices, jobs) }
            .sortedWith(compareBy({ it.deviceName.lowercase() }, { it.sortOrder }, { it.name.lowercase() }))
    }

    fun projects(deviceId: String): Flow<List<ProjectUi>> = allProjects.map { all -> all.filter { it.deviceId == deviceId }.sortedBy { it.sortOrder } }

    fun project(projectId: String): Flow<ProjectUi?> = allProjects.map { all -> all.firstOrNull { it.id == projectId } }

    private fun toUi(p: ProjectEntity, devices: List<DeviceEntity>, jobs: List<JobEntity>): ProjectUi? {
        val d = devices.firstOrNull { it.id == p.deviceId } ?: return null
        val meta = projectMeta(p)
        val mine = jobs.filter { it.projectId == p.id }
        val state = when {
            mine.any { it.status == JobStatuses.RUNNING } -> ProjectState.RUNNING
            mine.any { it.status == JobStatuses.WAITING_LOGIN } -> ProjectState.WAITING_LOGIN
            mine.isNotEmpty() -> ProjectState.QUEUED
            else -> ProjectState.IDLE
        }
        return ProjectUi(p.id, p.deviceId, d.name, meta?.name ?: "🔒 Locked project", meta?.devBranch ?: "",
            p.defaultSessionId, p.sortOrder, state, d.online, meta == null)
    }

    fun sessions(projectId: String): Flow<List<SessionUi>> = combine(dao.sessions(projectId), store.flow) { list, _ ->
        list.mapNotNull { s ->
            val p = dao.project(s.projectId) ?: return@mapNotNull null
            val meta: SessionMeta? = decrypt(p.deviceId, Aad.meta(p.deviceId, ObjectTypes.SESSION_META, s.id), s.encMeta)
            SessionUi(s.id, s.projectId, meta?.name ?: "🔒 Locked session", meta == null)
        }
    }

    fun session(sessionId: String): Flow<SessionUi?> = combine(dao.sessionFlow(sessionId), store.flow) { s, _ ->
        s?.let {
            val p = dao.project(it.projectId) ?: return@combine null
            val meta: SessionMeta? = decrypt(p.deviceId, Aad.meta(p.deviceId, ObjectTypes.SESSION_META, it.id), it.encMeta)
            SessionUi(it.id, it.projectId, meta?.name ?: "🔒 Locked session", meta == null)
        }
    }

    fun chat(sessionId: String): Flow<List<ChatItem>> = combine(dao.messages(sessionId), dao.jobs(sessionId), store.flow) { msgs, jobs, _ ->
        val s = dao.session(sessionId) ?: return@combine emptyList()
        val p = dao.project(s.projectId) ?: return@combine emptyList()
        val dev = p.deviceId
        val jobsById = jobs.associateBy { it.id }
        msgs.map { m ->
            when (m.role) {
                "user" -> {
                    val plain: PromptPlain? = decrypt(dev, Aad(dev, ObjectTypes.MESSAGE, m.id, Directions.P2D, m.clientCreatedAtMs), m.ciphertext)
                    val job = m.jobId?.let { jobsById[it] }
                    val err: ErrorPlain? = job?.encError?.let { decrypt(dev, Aad(dev, ObjectTypes.JOB_ERROR, job.id, Directions.D2P, 0), it) }
                    ChatItem(m.id, "user", plain?.text ?: "🔒 Can't decrypt (key missing)", m.clientCreatedAtMs, m.jobId,
                        job?.status, err?.let { e -> listOfNotNull(e.summary, e.detail).joinToString("\n") } ?: job?.errorCode)
                }
                else -> {
                    val plain: ResultPlain? = decrypt(dev, Aad(dev, ObjectTypes.MESSAGE, m.id, Directions.D2P, m.clientCreatedAtMs), m.ciphertext)
                    ChatItem(m.id, m.role, plain?.text ?: "🔒 Can't decrypt (key missing)", m.clientCreatedAtMs, m.jobId, null, null,
                        plain?.costUsd, plain?.commitHash)
                }
            }
        }
    }

    fun recentReplies(projectId: String): Flow<List<ChatItem>> = combine(dao.recentReplies(projectId, 20), store.flow) { msgs, _ ->
        val p = dao.project(projectId) ?: return@combine emptyList()
        msgs.map { m ->
            val plain: ResultPlain? = decrypt(p.deviceId, Aad(p.deviceId, ObjectTypes.MESSAGE, m.id, Directions.D2P, m.clientCreatedAtMs), m.ciphertext)
            ChatItem(m.id, m.role, plain?.text ?: "🔒", m.clientCreatedAtMs, m.jobId, null, null)
        }
    }

    fun activeJobs(sessionId: String): Flow<List<JobEntity>> = dao.jobs(sessionId).map { l -> l.filter { it.status in JobStatuses.ACTIVE } }

    /** Replies the user has not seen in a chat (or heard read aloud) and has not answered since. */
    private val unseenReplies: Flow<List<ReplyRow>> = combine(dao.replyRows(), settings.flow) { rows, s ->
        rows.filter { r ->
            val seen = s.seenSeq[r.sessionId]
            if (seen != null) r.seq > seen else r.createdAtMs > s.unseenBaselineMs
        }
    }
    val unseenSessions: Flow<Set<String>> = unseenReplies.map { l -> l.map { it.sessionId }.toSet() }.distinctUntilChanged()
    val unseenProjects: Flow<Set<String>> = unseenReplies.map { l -> l.map { it.projectId }.toSet() }.distinctUntilChanged()

    /** How many unseen replies each project / device has (shown as a number badge on the Projects and Devices pages). */
    val unseenCountByProject: Flow<Map<String, Int>> = unseenReplies.map { l -> countBy(l) { r -> r.projectId } }.distinctUntilChanged()
    val unseenCountByDevice: Flow<Map<String, Int>> = unseenReplies.map { l -> countBy(l) { r -> r.deviceId } }.distinctUntilChanged()

    private fun countBy(rows: List<ReplyRow>, key: (ReplyRow) -> String): Map<String, Int> {
        val counts = HashMap<String, Int>()
        for (r in rows) counts[key(r)] = (counts[key(r)] ?: 0) + 1
        return counts
    }

    /** The user has the chat of [sessionId] open: everything in it counts as seen. */
    suspend fun markSeen(sessionId: String) = withContext(Dispatchers.IO) { settings.markSeen(sessionId, dao.maxSeq(sessionId)) }

    fun commits(projectId: String): Flow<List<CommitUi>> = combine(dao.commits(projectId), store.flow) { list, _ ->
        val p = dao.project(projectId) ?: return@combine emptyList()
        list.map { c ->
            val meta: CommitMeta? = decrypt(p.deviceId, Aad.meta(p.deviceId, ObjectTypes.COMMIT_META, c.id), c.encMeta)
            CommitUi(c.id, c.gitHash, c.kind, meta?.message ?: "🔒", meta?.filesChanged ?: 0, meta?.insertions ?: 0,
                meta?.deletions ?: 0, c.revertedBy != null, c.createdAtMs)
        }
    }

    // ---------------- Refresh from server ----------------
    suspend fun refreshAll() {
        refreshDevices()
        runCatching { _catalog.value = http.api().models().filter { it.enabled } }
    }

    suspend fun refreshDevices() = withContext(Dispatchers.IO) {
        val list = http.api().devices()
        dao.upsertDevices(list.map { DeviceEntity(it.id, it.name, it.keyVersion, it.keyCheck, it.online, it.lastOnlineAtMs, it.createdAtMs) })
        val ids = list.map { it.id }
        dao.deleteDevicesExcept(ids)
        dao.deleteProjectsOfMissingDevices(ids)
        for (d in list) runCatching { refreshProjects(d.id) }
    }

    suspend fun refreshProjects(deviceId: String) = withContext(Dispatchers.IO) {
        val list = http.api().projects(deviceId)
        dao.upsertProjects(list.map { ProjectEntity(it.id, it.deviceId, it.encMeta, it.defaultSessionId, it.sortOrder, it.updatedAtMs) })
        dao.deleteProjectsExcept(deviceId, list.map { it.id })
        runCatching {
            val active = http.api().activeJobs(deviceId)
            dao.upsertJobs(active.map(::toEntity))
        }
    }

    suspend fun refreshSessions(projectId: String) = withContext(Dispatchers.IO) {
        val list = http.api().sessions(projectId)
        dao.upsertSessions(list.map { SessionEntity(it.id, it.projectId, it.encMeta, it.createdAtMs, it.updatedAtMs) })
        dao.deleteSessionsExcept(projectId, list.map { it.id })
    }

    /** Pulls new messages after the last known seq, then job states (docs/design.md §10.2 "resyncs history"). */
    suspend fun syncMessages(sessionId: String) = withContext(Dispatchers.IO) {
        var after = dao.maxSeq(sessionId)
        while (true) {
            val page = http.api().messages(sessionId, after)
            if (page.isEmpty()) break
            dao.upsertMessages(page.map { MessageEntity(it.id, it.sessionId, it.seq, it.role, it.jobId, it.ciphertext, it.clientCreatedAtMs, it.createdAtMs) })
            after = page.maxOf { it.seq }
            if (page.size < 200) break
        }
        dao.upsertJobs(http.api().jobs(sessionId).map(::toEntity))
    }

    suspend fun refreshCommits(projectId: String) = withContext(Dispatchers.IO) {
        val list = http.api().commits(projectId)
        dao.upsertCommits(list.map { CommitEntity(it.id, it.projectId, it.jobId, it.gitHash, it.kind, it.encMeta, it.revertedBy, it.createdAtMs) })
    }

    private fun toEntity(j: JobDto) = JobEntity(j.id, j.deviceId, j.projectId, j.sessionId, j.promptMessageId, j.status, j.errorCode,
        j.encError, j.model, j.effort, j.queuedAtMs, j.finishedAtMs)

    // ---------------- Prompts & jobs ----------------
    suspend fun sendPrompt(sessionId: String, text: String, model: String, effort: String) = withContext(Dispatchers.IO) {
        val s = dao.session(sessionId) ?: throw UserException("Unknown session.")
        val p = dao.project(s.projectId) ?: throw UserException("Unknown project.")
        val d = dao.device(p.deviceId) ?: throw UserException("Unknown PC.")
        val key = currentKey(d.id, d.keyVersion)
        val m = model.takeUnless { it == "default" }
        val e = effort.takeUnless { it == "default" }
        val messageId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val ciphertext = E2E.encrypt(key, d.keyVersion, Aad(d.id, ObjectTypes.MESSAGE, messageId, Directions.P2D, now),
            PromptPlain(text, p.id, s.id, m, e).toJsonBytes())
        val res = http.api().sendPrompt(sessionId, SendPromptRequest(messageId, ciphertext, now, m, e))
        dao.upsertMessages(listOf(MessageEntity(messageId, sessionId, res.seq, "user", res.jobId, ciphertext, now, now)))
        dao.upsertJobs(listOf(JobEntity(res.jobId, d.id, p.id, sessionId, messageId, JobStatuses.QUEUED, null, null, m, e, now, null)))
        settings.markSeen(sessionId, res.seq) // sending another message clears an unseen reply of this session
        settings.setModel(model, effort)
        res
    }

    suspend fun cancel(jobId: String) = withContext(Dispatchers.IO) { check(http.api().cancelJob(jobId)) }

    // ---------------- Control commands (docs/design.md §7.5) ----------------
    suspend fun control(projectId: String, cmd: ControlPlain): ControlResultPlain = withContext(Dispatchers.IO) {
        val d = deviceOf(projectId)
        if (!d.online) throw UserException("${d.name} is offline.")
        val key = currentKey(d.id, d.keyVersion)
        val requestId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val ct = E2E.encrypt(key, d.keyVersion, Aad(d.id, ObjectTypes.CONTROL, requestId, Directions.P2D, now), cmd.toJsonBytes())
        val waiter = CompletableDeferred<ControlResultPlain>()
        pendingControl[requestId] = waiter
        try {
            val res = http.api().control(projectId, ControlRequest(requestId, ct, now))
            if (res.code() == 409) throw UserException("${d.name} is offline.")
            check(res)
            withTimeout(90_000) { waiter.await() }
        } finally {
            pendingControl.remove(requestId)
        }
    }

    private suspend fun controlOk(projectId: String, cmd: ControlPlain): ControlResultPlain {
        val r = control(projectId, cmd)
        if (!r.ok) throw UserException(r.error ?: "The PC could not do that.")
        return r
    }

    suspend fun readMemory(projectId: String): String = controlOk(projectId, ControlPlain(ControlCommands.MEMORY_GET, projectId)).text ?: ""
    suspend fun writeMemory(projectId: String, text: String) { controlOk(projectId, ControlPlain(ControlCommands.MEMORY_SET, projectId, text = text)) }
    suspend fun rollback(projectId: String, hash: String) { controlOk(projectId, ControlPlain(ControlCommands.ROLLBACK, projectId, commitHash = hash)); refreshCommits(projectId) }
    suspend fun projectStatus(projectId: String) = controlOk(projectId, ControlPlain(ControlCommands.STATUS, projectId)).status

    // ---------------- Projects & sessions ----------------
    suspend fun renameProject(projectId: String, name: String) = withContext(Dispatchers.IO) {
        val p = dao.project(projectId) ?: throw UserException("Unknown project.")
        val d = dao.device(p.deviceId) ?: throw UserException("Unknown PC.")
        val meta = projectMeta(p) ?: throw UserException("Add this PC's key first.")
        val enc = E2E.encrypt(currentKey(d.id, d.keyVersion), d.keyVersion, Aad.meta(d.id, ObjectTypes.PROJECT_META, p.id),
            ProjectMeta(name.trim(), meta.devBranch).toJsonBytes())
        check(http.api().patchProject(projectId, PatchProjectRequest(encMeta = enc)))
        dao.upsertProjects(listOf(p.copy(encMeta = enc)))
    }

    suspend fun moveProject(projectId: String, newOrder: Int) = withContext(Dispatchers.IO) {
        val p = dao.project(projectId) ?: return@withContext
        check(http.api().patchProject(projectId, PatchProjectRequest(sortOrder = newOrder)))
        dao.upsertProjects(listOf(p.copy(sortOrder = newOrder)))
    }

    suspend fun setDefaultSession(projectId: String, sessionId: String) = withContext(Dispatchers.IO) {
        val p = dao.project(projectId) ?: return@withContext
        check(http.api().patchProject(projectId, PatchProjectRequest(defaultSessionId = sessionId)))
        dao.upsertProjects(listOf(p.copy(defaultSessionId = sessionId)))
    }

    suspend fun createSession(projectId: String, name: String): String = withContext(Dispatchers.IO) {
        val d = deviceOf(projectId)
        val id = UUID.randomUUID().toString()
        val enc = E2E.encrypt(currentKey(d.id, d.keyVersion), d.keyVersion, Aad.meta(d.id, ObjectTypes.SESSION_META, id),
            SessionMeta(name.trim()).toJsonBytes())
        check(http.api().putSession(id, PutSessionRequest(projectId, enc)))
        val now = System.currentTimeMillis()
        dao.upsertSessions(listOf(SessionEntity(id, projectId, enc, now, now)))
        // The PC also learns about it via sync.changed; tell it directly if it is online.
        if (d.online) runCatching { control(projectId, ControlPlain(ControlCommands.SESSION_CREATE, projectId, sessionId = id, name = name.trim())) }
        id
    }

    suspend fun renameSession(sessionId: String, name: String) = withContext(Dispatchers.IO) {
        val s = dao.session(sessionId) ?: throw UserException("Unknown session.")
        val d = deviceOf(s.projectId)
        val enc = E2E.encrypt(currentKey(d.id, d.keyVersion), d.keyVersion, Aad.meta(d.id, ObjectTypes.SESSION_META, s.id),
            SessionMeta(name.trim()).toJsonBytes())
        check(http.api().patchSession(sessionId, PatchSessionRequest(enc)))
        dao.upsertSessions(listOf(s.copy(encMeta = enc)))
    }

    /** Multimode target: the project's default session, or the most recently used one (docs/design.md §10.3). */
    suspend fun defaultSession(projectId: String): String? = withContext(Dispatchers.IO) {
        dao.project(projectId)?.defaultSessionId?.let { return@withContext it }
        runCatching { refreshSessions(projectId) }
        dao.sessionsByRecent(projectId).firstOrNull()?.id
    }

    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        check(http.api().deleteSession(sessionId))
        dao.deleteSession(sessionId)
        dao.clearMessages(sessionId)
        dao.clearJobs(sessionId)
    }

    /** Clear memory: the PC clears the server chat and starts a fresh Claude session (docs/design.md §8.5). */
    suspend fun clearMemory(sessionId: String) = withContext(Dispatchers.IO) {
        val s = dao.session(sessionId) ?: throw UserException("Unknown session.")
        controlOk(s.projectId, ControlPlain(ControlCommands.SESSION_CLEAR_MEMORY, s.projectId, sessionId = sessionId))
        dao.clearMessages(sessionId)
        dao.clearJobs(sessionId)
    }

    // ---------------- Devices & installs ----------------
    suspend fun renameDevice(id: String, name: String) = withContext(Dispatchers.IO) { check(http.api().renameDevice(id, RenameRequest(name.trim()))); refreshDevices() }

    suspend fun removeDevice(id: String) = withContext(Dispatchers.IO) {
        check(http.api().deleteDevice(id))
        store.removeDeviceKeys(id)
        refreshDevices()
    }

    suspend fun installs(): List<InstallDto> = withContext(Dispatchers.IO) { http.api().installs() }
    suspend fun revokeInstall(id: String) = withContext(Dispatchers.IO) { check(http.api().revokeInstall(id)) }
    suspend fun renameInstall(id: String, name: String) = withContext(Dispatchers.IO) { check(http.api().renameInstall(id, RenameRequest(name.trim()))) }

    /** "Disconnect this phone": revoke itself, then forget tokens, keys and the cache. */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        runCatching { http.api().revokeInstall(store.data.installId, confirmSelf = true) }
        ws.stop()
        http.clearTokens()
        store.clear()
        wipeCache()
        http.revoked.value = false
    }

    private suspend fun wipeCache() {
        dao.wipeMessages(); dao.wipeJobs(); dao.wipeCommits(); dao.wipeSessions(); dao.wipeProjects(); dao.wipeDevices()
    }

    private fun check(res: Response<Unit>) {
        if (!res.isSuccessful) throw UserException(
            when (res.code()) {
                404 -> "Not found on the server."
                409 -> "Conflict - the PC may be offline or the item changed."
                426 -> "Update Codenvi."
                429 -> "Too many requests - wait a moment."
                else -> "Server error (${res.code()})."
            }
        )
    }

    // ---------------- WebSocket events ----------------
    private suspend fun handle(env: Envelope) {
        when (env.type) {
            MessageTypes.JOB_STATUS -> {
                val p: JobStatusPayload = env.payload.convertTo()
                val existing = dao.job(p.jobId)
                if (existing != null) dao.updateJob(p.jobId, p.status, p.errorCode, p.encError ?: existing.encError, p.finishedAtMs)
                else p.sessionId?.let { runCatching { syncMessages(it) } }
                if (p.status !in JobStatuses.ACTIVE) _progress.value = _progress.value - p.jobId
            }
            MessageTypes.JOB_PROGRESS -> {
                val p: JobProgressPayload = env.payload.convertTo()
                val job = dao.job(p.jobId)
                val deviceId = job?.deviceId ?: dao.session(p.sessionId)?.let { dao.project(it.projectId)?.deviceId } ?: return
                val line: ProgressPlain = decrypt(deviceId, Aad(deviceId, ObjectTypes.PROGRESS, p.messageId, Directions.D2P, p.clientCreatedAtMs), p.ciphertext) ?: return
                val lines = (_progress.value[p.jobId] ?: emptyList()) + line.line
                _progress.value = _progress.value + (p.jobId to lines.takeLast(200))
                if (job != null && job.status != JobStatuses.RUNNING) dao.updateJob(job.id, JobStatuses.RUNNING, null, null, null)
            }
            MessageTypes.JOB_RESULT -> {
                val p: JobResultPayload = env.payload.convertTo()
                runCatching { syncMessages(p.sessionId) }
            }
            MessageTypes.CONTROL_RESPONSE -> {
                val p: ControlResponsePayload = env.payload.convertTo()
                val waiter = pendingControl[p.requestId] ?: return
                val result: ControlResultPlain? = decrypt(p.deviceId,
                    Aad(p.deviceId, ObjectTypes.CONTROL_RESPONSE, p.requestId, Directions.D2P, p.clientCreatedAtMs), p.ciphertext)
                waiter.complete(result ?: ControlResultPlain(false, "The PC's answer could not be verified."))
            }
            MessageTypes.DEVICE_PRESENCE -> {
                val p: DevicePresencePayload = env.payload.convertTo()
                dao.setOnline(p.deviceId, p.online)
            }
            MessageTypes.SYNC_CHANGED -> {
                val p: SyncChangedPayload = env.payload.convertTo()
                runCatching {
                    when (p.entity) {
                        "device" -> refreshDevices()
                        "project" -> p.parentId?.let { refreshProjects(it) } ?: refreshDevices()
                        "session" -> p.parentId?.let { refreshSessions(it) }
                        "messages" -> { dao.clearMessages(p.id); syncMessages(p.id) }
                        "commits" -> refreshCommits(p.id)
                        else -> Unit
                    }
                }
            }
        }
    }
}

/** Maps exceptions to short user-facing text. */
object RevokedOrUser {
    fun message(e: Throwable): String = when (e) {
        is UserException -> e.message ?: "Something went wrong."
        is app.codenvi.net.RevokedException -> "This app was disconnected from the server."
        is kotlinx.coroutines.TimeoutCancellationException -> "The PC didn't answer in time."
        is HttpException -> when (e.code()) {
            401 -> "This app was disconnected from the server."
            404 -> "Not found on the server."
            409 -> "The PC is offline or the item changed."
            426 -> "Update Codenvi."
            429 -> "Too many requests - wait a moment."
            else -> "Server error (${e.code()})."
        }
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException -> "Can't reach the server."
        is javax.net.ssl.SSLException -> "Secure connection failed: ${e.message}"
        is IllegalArgumentException -> e.message ?: "Invalid input."
        else -> e.message ?: e.javaClass.simpleName
    }
}
