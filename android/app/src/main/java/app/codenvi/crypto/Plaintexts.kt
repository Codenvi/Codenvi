package app.codenvi.crypto

import com.squareup.moshi.JsonClass

// Plaintext JSON shapes (docs/crypto.md §7). Mirrors shared/Codenvi.Contracts/Plaintexts.cs.

@JsonClass(generateAdapter = true)
data class PromptPlain(val text: String, val projectId: String, val sessionId: String, val model: String?, val effort: String?)

@JsonClass(generateAdapter = true)
data class ResultPlain(
    val text: String,
    val costUsd: Double? = null,
    val durationMs: Long? = null,
    val commitHash: String? = null,
    val filesChanged: Int? = null,
    val part: Int = 1,
    val parts: Int = 1,
)

@JsonClass(generateAdapter = true)
data class ProgressPlain(val line: String)

/** [hasScript] is only a hint that the PC has a shell script for this project. The script text never leaves the PC. */
@JsonClass(generateAdapter = true)
data class ProjectMeta(val name: String, val devBranch: String, val hasScript: Boolean = false, val scriptRunning: Boolean = false)

@JsonClass(generateAdapter = true)
data class SessionMeta(val name: String)

@JsonClass(generateAdapter = true)
data class CommitMeta(val message: String, val filesChanged: Int, val insertions: Int, val deletions: Int)

@JsonClass(generateAdapter = true)
data class ErrorPlain(val summary: String, val detail: String?)

/** The "project id" of PC-level control commands (no project involved). */
const val NO_PROJECT = "00000000-0000-0000-0000-000000000000"

object ControlCommands {
    const val SESSION_CREATE = "session.create"
    const val SESSION_CLEAR_MEMORY = "session.clearMemory"
    const val MEMORY_GET = "project.memory.get"
    const val MEMORY_SET = "project.memory.set"
    const val ROLLBACK = "project.rollback"
    const val STATUS = "project.status"
    const val CHANGES = "project.changes"
    const val DIFF = "project.diff"
    const val RUN_SCRIPT = "project.script.run"
    const val STOP_SCRIPT = "project.script.stop"
    const val COMMIT = "project.commit"
    const val PUBLISH = "project.publish"
    const val PULL = "project.pull"
    /** PC-level: projectId is the all-zero id; the PC creates a folder named [ControlPlain.name] in its projects location and registers it. */
    const val PROJECT_CREATE = "project.create"
    /** Removes the project from Codenvi and the server; the PC never deletes its folder, branch or commits. */
    const val PROJECT_DELETE = "project.delete"
}

@JsonClass(generateAdapter = true)
data class ControlPlain(
    val command: String,
    val projectId: String,
    val sessionId: String? = null,
    val name: String? = null,
    val text: String? = null,
    val commitHash: String? = null,
)

@JsonClass(generateAdapter = true)
data class ProjectStatusPlain(
    val branch: String,
    val dirty: Boolean,
    val lastCommitHash: String?,
    val lastCommitMessage: String?,
    val runningJobId: String?,
    val branchOk: Boolean,
    val aheadOfMain: Int = 0,
    val behindOrigin: Int = 0,
)

@JsonClass(generateAdapter = true)
data class ControlResultPlain(
    val ok: Boolean,
    val error: String? = null,
    val text: String? = null,
    val status: ProjectStatusPlain? = null,
)
