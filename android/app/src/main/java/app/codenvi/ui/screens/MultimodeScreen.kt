package app.codenvi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codenvi.data.AppSettings
import app.codenvi.repo.UserException
import app.codenvi.ui.AppViewModel
import app.codenvi.ui.components.StatusDot
import app.codenvi.ui.components.color
import app.codenvi.ui.theme.Tokens
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/**
 * Multimode (Project.md §10.3 screen 6): one input at the top, a radio list of every project on every PC
 * below. Send goes to the selected project's default session; the selection stays after sending.
 */
@Composable
fun MultimodeScreen(vm: AppViewModel, onOpenChat: (String) -> Unit) {
    val repo = vm.repo
    val projects by repo.allProjects.collectAsStateWithLifecycle(emptyList())
    val settings by repo.settings.flow.collectAsStateWithLifecycle(AppSettings())
    val selectedId = settings.multimodeProject?.takeIf { id -> projects.any { it.id == id } } ?: projects.firstOrNull { !it.locked }?.id
    val selected = projects.firstOrNull { it.id == selectedId }
    val replies by remember(selectedId) { selectedId?.let { repo.recentReplies(it) } ?: emptyFlow() }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { vm.run { repo.refreshDevices() } }

    Column(Modifier.fillMaxSize().imePadding()) {
        CodenviTopBar("Multimode")

        // Top: input, model/effort dropdowns, mic and Send (shared PromptBar).
        PromptBar(vm, enabled = selected != null && !selected.locked) { text, model, effort ->
            val target = selected ?: throw UserException("Pick a project below.")
            val sessionId = repo.defaultSession(target.id) ?: throw UserException("That project has no session yet. Create one on the project screen.")
            repo.sendPrompt(sessionId, text, model, effort)
        }

        // Compact reply feed for the selected project.
        if (selected != null && replies.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 180.dp).padding(horizontal = 12.dp, vertical = 6.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                replies.take(5).forEach { r ->
                    Text(
                        r.text.replace(Regex("\\s+"), " ").take(220),
                        color = Tokens.OnSurface, style = MaterialTheme.typography.bodyMedium, maxLines = 3,
                        modifier = Modifier.fillMaxWidth().background(Tokens.SurfaceVariant, RoundedCornerShape(10.dp))
                            .clickable { scope.launch { repo.defaultSession(selected.id)?.let(onOpenChat) } }.padding(10.dp),
                    )
                }
            }
        }

        Text("Projects", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(projects, key = { it.id }) { p ->
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(selected = p.id == selectedId, enabled = !p.locked, role = Role.RadioButton,
                            onClick = { scope.launch { repo.settings.setMultimodeProject(p.id) } })
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = p.id == selectedId, onClick = null, enabled = !p.locked,
                        colors = RadioButtonDefaults.colors(selectedColor = Tokens.Primary))
                    Text("${p.name} · ${p.deviceName}", modifier = Modifier.weight(1f), maxLines = 1,
                        color = if (p.locked) Tokens.OnSurfaceMuted else Tokens.OnSurface)
                    StatusDot(if (!p.deviceOnline) Tokens.OnSurfaceMuted else p.state.color())
                    Spacer(Modifier.width(12.dp))
                }
            }
        }
    }
}
