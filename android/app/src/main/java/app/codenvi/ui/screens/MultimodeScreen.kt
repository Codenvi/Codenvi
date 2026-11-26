package app.codenvi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codenvi.data.AppSettings
import app.codenvi.repo.ProjectState
import app.codenvi.repo.UserException
import app.codenvi.ui.AppViewModel
import app.codenvi.ui.components.ChangesIcon
import app.codenvi.ui.components.ChatIcon
import app.codenvi.ui.theme.Tokens
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/**
 * Multimode (docs/design.md §10.3 screen 6): pick a project in the grid, pick one of its sessions above the input,
 * and send. The title bar has a Code changes icon (the project's uncommitted files) and a chat icon that opens that session's
 * chat (and the chat page has an icon back to here).
 */
@Composable
fun MultimodeScreen(vm: AppViewModel, onOpenChat: (String) -> Unit, onOpenChanges: (String) -> Unit = {}) {
    val repo = vm.repo
    val projects by repo.allProjects.collectAsStateWithLifecycle(emptyList())
    val settings by repo.settings.flow.collectAsStateWithLifecycle(AppSettings())
    val selectedId = settings.multimodeProject?.takeIf { id -> projects.any { it.id == id } } ?: projects.firstOrNull { !it.locked }?.id
    val selected = projects.firstOrNull { it.id == selectedId }
    val sessions by remember(selectedId) { selectedId?.let { repo.sessions(it) } ?: emptyFlow() }.collectAsStateWithLifecycle(emptyList())
    // The chosen session if it belongs to this project, else the project's default session, else the first usable one.
    val usable = sessions.filter { !it.locked }
    val sessionId = usable.firstOrNull { it.id == settings.multimodeSession }?.id
        ?: usable.firstOrNull { it.id == selected?.defaultSessionId }?.id
        ?: usable.firstOrNull()?.id
    val scope = rememberCoroutineScope()
    // Active jobs of the selected session lock the prompt bar until the reply arrives.
    val activeJobs by remember(sessionId) { sessionId?.let { repo.activeJobs(it) } ?: emptyFlow() }.collectAsStateWithLifecycle(emptyList())
    val unseen by repo.unseenProjects.collectAsStateWithLifecycle(emptySet())
    val unseenSessions by repo.unseenSessions.collectAsStateWithLifecycle(emptySet())

    // Commit / Pull / Publish for the selected project, exactly as in the chat header (they leave their markers in the chat of
    // the selected session). The state is rebuilt when another project or session is picked, so no old status lingers.
    val git = rememberGitActions(vm, sessionId ?: "", selected?.id)
    val gitProject = if (sessionId != null) selected else null
    // Re-check the PC's git state when a project is picked, when it comes online, and when a job finishes (it leaves changes behind).
    LaunchedEffect(gitProject?.id, gitProject?.deviceOnline, activeJobs.isEmpty()) {
        gitProject?.let { if (it.deviceOnline) git.refresh(it.id) }
    }

    LaunchedEffect(Unit) { vm.run { repo.refreshDevices() } }
    LaunchedEffect(selectedId) { selectedId?.let { id -> runCatching { repo.refreshSessions(id) } } }

    Column(Modifier.fillMaxSize().imePadding()) {
        CodenviTopBar("Multimode") {
            GitHeaderButtons(git, gitProject)
            // Code changes of the selected project (opened through the selected session, like the chat page does).
            IconButton(enabled = sessionId != null, onClick = { sessionId?.let(onOpenChanges) }) { Icon(ChangesIcon, "Code changes") }
            IconButton(enabled = sessionId != null, onClick = { sessionId?.let(onOpenChat) }) {
                // White dot on the chat icon when the active project has an unseen reply.
                BadgedBox(badge = { if (selected != null && selected.id in unseen) Badge(containerColor = Color.White) }) {
                    Icon(ChatIcon, "Open chat")
                }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3), modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(projects, key = { it.id }) { p ->
                val active = p.id == selectedId
                val shape = RoundedCornerShape(12.dp)
                Box(
                    Modifier.fillMaxWidth().clip(shape)
                        .background(if (active) Tokens.Primary else Tokens.Surface, shape)
                        .border(1.dp, if (active) Tokens.Primary else Tokens.Outline, shape)
                        .selectable(selected = active, enabled = !p.locked, role = Role.RadioButton,
                            onClick = { scope.launch { repo.settings.setMultimodeProject(p.id) } })
                ) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(10.dp)) {
                        Column(Modifier.align(Alignment.CenterStart).padding(end = 10.dp)) {
                            Text(p.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold,
                                color = if (active) Color.White else if (p.locked) Tokens.OnSurfaceMuted else Tokens.OnSurface)
                            Text(p.deviceName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                                color = if (active) Color.White.copy(alpha = 0.85f) else Tokens.OnSurfaceMuted)
                        }
                        // White dot (top right): a reply in one of this project's sessions that was not seen in the chat yet.
                        if (p.id in unseen) Box(
                            Modifier.align(Alignment.TopEnd).size(11.dp).background(Color.White, CircleShape)
                                .border(1.5.dp, if (active) Tokens.Primary else Tokens.Surface, CircleShape),
                        )
                    }
                    // Loading bar along the bottom edge while the project is working on a prompt.
                    if (p.state != ProjectState.IDLE) LinearProgressIndicator(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                        color = if (active) Color.White else Tokens.Primary,
                        trackColor = if (active) Color.White.copy(alpha = 0.3f) else Tokens.SurfaceVariant,
                    )
                }
            }
        }

        // At the bottom, like on the chat page: the input with the session row above it, so one tap chooses which session of the
        // project receives the prompt.
        PromptBar(
            vm,
            enabled = selected != null && !selected.locked && sessionId != null,
            // Multimode only: read the newest reply of the selected session aloud (the chat page has no such button here).
            topRight = { ReadAloudButton(vm, sessionId) },
            busy = activeJobs.isNotEmpty(),
            onStop = { activeJobs.firstOrNull()?.let { j -> vm.run { repo.cancel(j.id) } } },
            header = {
                if (selected != null) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (usable.isEmpty()) {
                            item {
                                Text("No sessions yet. Create one on the project screen.", color = Tokens.OnSurfaceMuted,
                                    style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        items(usable, key = { it.id }) { s ->
                            val on = s.id == sessionId
                            val shape = RoundedCornerShape(50)
                            Row(
                                Modifier.clip(shape)
                                    .background(if (on) Tokens.Primary else Tokens.SurfaceVariant, shape)
                                    .selectable(selected = on, role = Role.RadioButton,
                                        onClick = { scope.launch { repo.settings.setMultimodeSession(s.id) } })
                                    .padding(horizontal = 14.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // White dot before the name: this session has a reply that was not seen yet.
                                if (s.id in unseenSessions) {
                                    Box(Modifier.size(9.dp).background(Color.White, CircleShape)
                                        .border(1.dp, if (on) Tokens.Primary else Tokens.SurfaceVariant, CircleShape))
                                    Spacer(Modifier.width(7.dp))
                                }
                                Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge,
                                    color = if (on) Color.White else Tokens.OnSurface)
                            }
                        }
                    }
                }
            },
        ) { text, model, effort ->
            val target = sessionId ?: throw UserException("Pick a project and a session.")
            repo.sendPrompt(target, text, model, effort)
        }
    }
    GitActionDialogs(git, gitProject)
}
