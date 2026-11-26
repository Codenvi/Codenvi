package app.codenvi.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codenvi.repo.ChangeKind
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
        CodenviTopBar(session?.name ?: "Code changes", onBack, subtitle = project?.name) {
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
                else -> ChangesList(r)
            }
        }
    }
    GitActionDialogs(git, project)
}

@Composable
private fun Message(text: String) = Text(text, color = Tokens.OnSurfaceMuted, modifier = Modifier.padding(16.dp))

/** One row of the list: a folder path header or a changed file. */
private sealed interface ListRow {
    data class Dir(val dir: String, val collapsed: Boolean) : ListRow
    data class File(val dir: String, val name: String, val kind: ChangeKind) : ListRow
}

/**
 * The files grouped by their full folder path: the path once as a header, the files indented below it (no nested tree).
 * Scrolls vertically and, for long paths, horizontally; there is no visible scrollbar. File names take the colour of their dot.
 */
@Composable
private fun ChangesList(r: ChangesResult) {
    var collapsed by remember { mutableStateOf(setOf<String>()) } // folder paths; everything starts expanded
    val groups = remember(r) { Changes.groups(r.files) }
    val rows = remember(groups, collapsed) {
        buildList {
            for (g in groups) {
                add(ListRow.Dir(g.dir, g.dir in collapsed))
                if (g.dir !in collapsed) g.files.forEach { add(ListRow.File(g.dir, it.name, it.kind)) }
            }
        }
    }
    // Every row gets the same width: the widest of the longest paths/names (measured), or the screen width if that is wider,
    // so a long path can be scrolled sideways. The dots sit at the left edge and stay put while the list scrolls sideways.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MaterialTheme.typography.bodyMedium
    val textWidth = remember(groups) {
        val candidates = (groups.map { it.dir } + groups.flatMap { g -> g.files.map { it.name } }).sortedByDescending { it.length }.take(12)
        candidates.maxOfOrNull { with(density) { measurer.measure(it, style, softWrap = false).size.width.toDp() } } ?: 0.dp
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val rowWidth = maxOf(maxWidth, textWidth + 56.dp)
        val hScroll = rememberScrollState()
        Box(Modifier.fillMaxSize().horizontalScroll(hScroll)) {
            LazyColumn(Modifier.width(rowWidth).fillMaxHeight(), contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)) {
                itemsIndexed(rows, key = { _, row -> when (row) { is ListRow.Dir -> "d:" + row.dir; is ListRow.File -> "f:" + row.dir + "/" + row.name } }) { index, row ->
                    // Table-like stripes: every second row (the 2nd, 4th, …) has a slightly darker background.
                    // Opaque (not translucent) so the fixed dot can hide the name that slides under it.
                    val rowBg = if (index % 2 == 1) Color.Black.copy(alpha = 0.22f).compositeOver(Tokens.Background) else Tokens.Background
                    val stripe = Modifier.background(rowBg)
                    when (row) {
                        is ListRow.Dir -> Row(
                            Modifier.fillMaxWidth().then(stripe).clickable {
                                collapsed = if (row.dir in collapsed) collapsed - row.dir else collapsed + row.dir
                            }.padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (row.collapsed) "▸" else "▾", color = Tokens.OnSurfaceMuted, modifier = Modifier.width(20.dp))
                            Text(row.dir.ifEmpty { "/" }, style = style, color = Tokens.OnSurfaceMuted, softWrap = false, maxLines = 1)
                        }
                        is ListRow.File -> Box(Modifier.fillMaxWidth().then(stripe)) {
                            Text(row.name, style = style, color = row.kind.color(), softWrap = false, maxLines = 1,
                                modifier = Modifier.padding(start = 32.dp, end = 16.dp, top = 6.dp, bottom = 6.dp))
                            // The dot at the left edge. It is shifted by the sideways scroll, so it stays at the left of the
                            // screen while a long name scrolls under it.
                            Box(
                                Modifier.align(Alignment.CenterStart).fillMaxHeight()
                                    .offset { IntOffset(hScroll.value, 0) }
                                    .background(rowBg).padding(start = 12.dp, end = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) { StatusDot(row.kind.color(), Modifier.size(9.dp)) }
                        }
                    }
                }
                if (r.more > 0) item { Text("+ ${r.more} more files not shown", color = Tokens.OnSurfaceMuted, modifier = Modifier.padding(16.dp)) }
            }
        }
    }
}
