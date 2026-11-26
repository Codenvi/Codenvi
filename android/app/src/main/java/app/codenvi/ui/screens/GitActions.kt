package app.codenvi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.codenvi.crypto.ProjectStatusPlain
import app.codenvi.repo.ProjectUi
import app.codenvi.ui.AppViewModel
import app.codenvi.ui.components.ConfirmDialog
import app.codenvi.ui.components.PublishIcon
import app.codenvi.ui.components.PullIcon
import app.codenvi.ui.components.SaveIcon
import app.codenvi.ui.theme.Tokens

/**
 * The Commit / Pull / Publish buttons and their dialogs, shared by the chat header and the Code changes header so both
 * behave identically. State lives here; [GitHeaderButtons] draws the icons and [GitActionDialogs] the confirmations.
 */
@Stable
class GitActionsState(private val vm: AppViewModel, private val sessionId: String) {
    /** The PC's git state (null until known); decides which icons show. */
    var status by mutableStateOf<ProjectStatusPlain?>(null)
    var confirmCommit by mutableStateOf(false)
    var confirmPull by mutableStateOf(false)
    var confirmPublish by mutableStateOf(false)
    var committing by mutableStateOf(false)
    var pulling by mutableStateOf(false)
    var publishing by mutableStateOf(false)

    /** Called after a Commit, Pull or Publish succeeded (the Code changes page reloads its list here). */
    var onChanged: () -> Unit = {}

    fun refresh(projectId: String) {
        vm.run { status = runCatching { vm.repo.projectStatus(projectId) }.getOrNull() }
    }

    internal fun done(projectId: String, message: String) {
        vm.ok(message)
        refresh(projectId)
        onChanged()
    }

    internal fun commit(p: ProjectUi, message: String) {
        confirmCommit = false
        committing = true
        vm.run(onOk = { done(p.id, "Committed on the PC.") }) {
            try { vm.repo.commitLeftovers(p.id, sessionId, message) } finally { committing = false }
        }
    }

    internal fun pull(p: ProjectUi) {
        confirmPull = false
        pulling = true
        vm.run(onOk = { done(p.id, "Pulled origin/main on the PC.") }) {
            try { vm.repo.pull(p.id, sessionId) } finally { pulling = false }
        }
    }

    internal fun publish(p: ProjectUi) {
        confirmPublish = false
        publishing = true
        vm.run(onOk = { done(p.id, "Published to main.") }) {
            try { vm.repo.publish(p.id, sessionId) } finally { publishing = false }
        }
    }
}

@Composable
fun rememberGitActions(vm: AppViewModel, sessionId: String): GitActionsState = remember(sessionId) { GitActionsState(vm, sessionId) }

/**
 * Commit/Publish only show up once we know the PC's git state, and only while that PC is online.
 * While a commit/pull/publish is running its icon is replaced by a spinner (and stays put until the PC answers).
 */
@Composable
fun GitHeaderButtons(git: GitActionsState, project: ProjectUi?) {
    val online = project?.deviceOnline == true
    if (git.committing || (online && git.status?.dirty == true)) {
        IconButton(enabled = !git.committing, onClick = { git.confirmCommit = true }) {
            if (git.committing) CircularProgressIndicator(Modifier.size(20.dp), color = Tokens.Primary, strokeWidth = 2.dp)
            else Icon(SaveIcon, "Commit uncommitted changes on the PC")
        }
    }
    // Pull: only when origin/main has commits the dev branch does not have yet.
    if (git.pulling || (online && (git.status?.behindOrigin ?: 0) > 0)) {
        IconButton(enabled = !git.pulling, onClick = { git.confirmPull = true }) {
            if (git.pulling) CircularProgressIndicator(Modifier.size(20.dp), color = Tokens.Primary, strokeWidth = 2.dp)
            else Icon(PullIcon, "Pull from origin/main")
        }
    }
    if (git.publishing || (online && (git.status?.aheadOfMain ?: 0) > 0)) {
        IconButton(enabled = !git.publishing, onClick = { git.confirmPublish = true }) {
            if (git.publishing) CircularProgressIndicator(Modifier.size(20.dp), color = Tokens.Primary, strokeWidth = 2.dp)
            else Icon(PublishIcon, "Publish to main")
        }
    }
}

@Composable
fun GitActionDialogs(git: GitActionsState, project: ProjectUi?) {
    if (git.confirmCommit) project?.let { p ->
        var message by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { git.confirmCommit = false },
            title = { Text("Commit") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Commit the uncommitted changes on \"${p.name}\" (${p.deviceName}), branch ${p.devBranch}. This text becomes the whole commit message.",
                        color = Tokens.OnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = message, onValueChange = { message = it }, placeholder = { Text("Commit message") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 220.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = message.isNotBlank(), onClick = { git.commit(p, message) }) { Text("Commit") }
            },
            dismissButton = { TextButton(onClick = { git.confirmCommit = false }) { Text("Cancel") } },
        )
    }
    if (git.confirmPull) project?.let { p ->
        ConfirmDialog("Pull", "Fetch origin/main and merge it into ${p.devBranch}, on \"${p.name}\" (${p.deviceName})? Uncommitted changes must be committed first.", "Pull",
            onConfirm = { git.pull(p) }, onDismiss = { git.confirmPull = false })
    }
    if (git.confirmPublish) project?.let { p ->
        ConfirmDialog("Publish", "Merge ${p.devBranch} into main and push main to origin, on \"${p.name}\" (${p.deviceName})?", "Publish",
            onConfirm = { git.publish(p) }, onDismiss = { git.confirmPublish = false })
    }
}
