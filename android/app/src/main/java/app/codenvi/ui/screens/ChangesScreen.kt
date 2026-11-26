package app.codenvi.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codenvi.repo.ChangeKind
import app.codenvi.repo.ChangeNode
import app.codenvi.repo.Changes
import app.codenvi.repo.ChangesResult
import app.codenvi.repo.RevokedOrUser
import app.codenvi.ui.AppViewModel
import app.codenvi.ui.components.ChatIcon
import app.codenvi.ui.components.StatusDot
import app.codenvi.ui.theme.Tokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

private val UpdatedBlue = Color(0xFF42A5F5)

private fun ChangeKind.color(): Color = when (this) {
    ChangeKind.NEW -> Tokens.Success
    ChangeKind.UPDATED -> UpdatedBlue
    ChangeKind.DELETED -> Tokens.Error
}

/**
 * Code changes: the uncommitted files of the chat's project on the PC as a folder tree (paths only, no diffs), with a dot on
 * the right of each file: green = new, blue = updated, red = deleted. The header has the same Commit / Pull / Publish buttons
 * as the chat, and the chat icon on the right goes back to it.
 */
@Composable
fun ChangesScreen(vm: AppViewModel, sessionId: String, onBack: () -> Unit) {
    val repo = vm.repo
    val session by remember(sessionId) { repo.session(sessionId) }.collectAsStateWithLifecycle(null)
    val project by remember(session?.projectId) { session?.projectId?.let { repo.project(it) } ?: emptyFlow() }.collectAsStateWithLifecycle(null)
    val git = rememberGitActions(vm, sessionId)
    val scope = rememberCoroutineScope()

    var result by remember { mutableStateOf<ChangesResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var collapsed by remember { mutableStateOf(setOf<String>()) } // folder paths; everything starts expanded

    fun load() {
        val p = project ?: return
        if (!p.deviceOnline) return
        loading = true
        scope.launch {
            try {
                result = repo.projectChanges(p.id)
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = RevokedOrUser.message(e)
            } finally { loading = false }
        }
        git.refresh(p.id)
    }
    git.onChanged = { load() }

    LaunchedEffect(project?.id, project?.deviceOnline) { load() }

    Column(Modifier.fillMaxSize()) {
        CodenviTopBar(project?.name ?: "Code changes", onBack) {
            GitHeaderButtons(git, project)
            IconButton(onClick = onBack) { Icon(ChatIcon, "Back to chat") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val r = result
            when {
                project?.deviceOnline == false -> Message("${project?.deviceName ?: "The PC"} is offline.")
                error != null && r == null -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(error ?: "", color = Tokens.Warning)
                    TextButton(onClick = { load() }) { Text("Retry") }
                }
                r == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Tokens.Primary) }
                r.files.isEmpty() -> Message(if (loading) "Loading…" else "No uncommitted changes.")
                else -> {
                    val rows = remember(r, collapsed) { Changes.flatten(Changes.tree(r.files), collapsed) }
                    // A plain vertically scrolling list: touch scrolling only, no visible scrollbar.
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)) {
                        items(rows, key = { (n, _) -> (if (n.isFolder) "d:" else "f:") + n.path }) { (node, depth) ->
                            ChangeRow(node, depth, collapsed = node.path in collapsed) {
                                collapsed = if (node.path in collapsed) collapsed - node.path else collapsed + node.path
                            }
                        }
                        if (r.more > 0) item { Text("+ ${r.more} more files not shown", color = Tokens.OnSurfaceMuted, modifier = Modifier.padding(8.dp)) }
                    }
                }
            }
        }
    }
    GitActionDialogs(git, project)
}

@Composable
private fun Message(text: String) = Text(text, color = Tokens.OnSurfaceMuted, modifier = Modifier.padding(16.dp))

@Composable
private fun ChangeRow(node: ChangeNode, depth: Int, collapsed: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (node.isFolder) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(start = (depth * 16).dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (node.isFolder) {
            Text(if (collapsed) "▸" else "▾", color = Tokens.OnSurfaceMuted, modifier = Modifier.width(20.dp))
            Text(node.name, style = MaterialTheme.typography.bodyMedium, color = Tokens.OnSurfaceMuted, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        } else {
            Spacer(Modifier.width(20.dp))
            Text(node.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            StatusDot(node.kind!!.color(), Modifier.size(9.dp))
        }
    }
}
