package app.codenvi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codenvi.repo.Diff
import app.codenvi.repo.DiffKind
import app.codenvi.repo.DiffResult
import app.codenvi.repo.RevokedOrUser
import app.codenvi.ui.AppViewModel
import app.codenvi.ui.components.ChangesIcon
import app.codenvi.ui.theme.MonoStyle
import app.codenvi.ui.theme.Tokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

private val AddedGreen = Tokens.Success
private val RemovedRed = Tokens.Error
private val HunkBlue = Color(0xFF42A5F5)

/**
 * What changed in one file (a unified diff against the last commit, read live from the PC). Added lines are green, removed lines red,
 * with line numbers; long lines scroll sideways. The back arrow and the changes icon both return to the Code changes page.
 */
@Composable
fun DiffScreen(vm: AppViewModel, sessionId: String, path: String, onBack: () -> Unit) {
    val repo = vm.repo
    val session by remember(sessionId) { repo.session(sessionId) }.collectAsStateWithLifecycle(null)
    val project by remember(session?.projectId) { session?.projectId?.let { repo.project(it) } ?: emptyFlow() }.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()

    var result by remember { mutableStateOf<DiffResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun load() {
        val p = project ?: return
        if (!p.deviceOnline) return
        loading = true
        scope.launch {
            try {
                result = Diff.parse(repo.fileDiff(p.id, path))
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = RevokedOrUser.message(e)
            } finally { loading = false }
        }
    }
    LaunchedEffect(project?.id, project?.deviceOnline) { load() }

    val name = path.substringAfterLast('/')
    Column(Modifier.fillMaxSize()) {
        CodenviTopBar(name, onBack, subtitle = path.substringBeforeLast('/', "").ifEmpty { project?.name }) {
            // Back to the Code changes page (same as the back arrow).
            IconButton(onClick = onBack) { Icon(ChangesIcon, "Back to code changes") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val r = result
            when {
                project?.deviceOnline == false -> Text("${project?.deviceName ?: "The PC"} is offline.", color = Tokens.OnSurfaceMuted, modifier = Modifier.padding(16.dp))
                error != null && r == null -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(error ?: "", color = Tokens.Warning)
                    TextButton(onClick = { load() }) { Text("Retry") }
                }
                r == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Tokens.Primary) }
                r.lines.isEmpty() -> Text("No changes in this file.", color = Tokens.OnSurfaceMuted, modifier = Modifier.padding(16.dp))
                else -> DiffView(r)
            }
        }
    }
}

@Composable
private fun DiffView(r: DiffResult) {
    // Every line gets the same width (the longest line, measured in the monospace font, or the screen), so the colours are full rows
    // and long lines can be scrolled sideways. There is no visible scrollbar.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val charWidth = remember { with(density) { measurer.measure("0", MonoStyle).size.width.toDp() } }
    val longest = remember(r) { minOf(r.lines.maxOfOrNull { it.text.length } ?: 0, 600) }
    val gutter = 48.dp
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("+${r.added}", color = AddedGreen, style = MaterialTheme.typography.labelLarge)
            Text("−${r.removed}", color = RemovedRed, style = MaterialTheme.typography.labelLarge)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val rowWidth = maxOf(maxWidth, gutter + charWidth * (longest + 3) + 24.dp)
            Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                LazyColumn(Modifier.width(rowWidth).fillMaxHeight(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    itemsIndexed(r.lines) { _, line ->
                        val bg = when (line.kind) {
                            DiffKind.ADDED -> AddedGreen.copy(alpha = 0.16f)
                            DiffKind.REMOVED -> RemovedRed.copy(alpha = 0.16f)
                            DiffKind.HUNK -> HunkBlue.copy(alpha = 0.14f)
                            else -> Color.Transparent
                        }
                        val marker = when (line.kind) { DiffKind.ADDED -> "+"; DiffKind.REMOVED -> "-"; else -> " " }
                        val color = when (line.kind) {
                            DiffKind.ADDED -> AddedGreen
                            DiffKind.REMOVED -> RemovedRed
                            DiffKind.HUNK -> HunkBlue
                            DiffKind.NOTE -> Tokens.OnSurfaceMuted
                            DiffKind.CONTEXT -> Tokens.OnSurface
                        }
                        Row(Modifier.fillMaxWidth().background(bg).padding(vertical = 1.dp)) {
                            Text(line.number?.toString() ?: "", style = MonoStyle, color = Tokens.OnSurfaceMuted, textAlign = TextAlign.End,
                                softWrap = false, maxLines = 1, modifier = Modifier.width(gutter).padding(end = 8.dp))
                            Text(if (line.kind == DiffKind.HUNK || line.kind == DiffKind.NOTE) line.text else marker + line.text,
                                style = MonoStyle, color = color, softWrap = false, maxLines = 1, modifier = Modifier.padding(end = 16.dp))
                        }
                    }
                }
            }
        }
    }
}
