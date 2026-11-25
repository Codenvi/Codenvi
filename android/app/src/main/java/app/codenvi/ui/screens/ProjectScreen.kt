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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codenvi.repo.CommitUi
import app.codenvi.repo.SessionUi
import app.codenvi.ui.AppViewModel
import app.codenvi.ui.components.Chip
import app.codenvi.ui.components.ConfirmDialog
import app.codenvi.ui.components.InputDialog
import app.codenvi.ui.components.SaveIcon
import app.codenvi.ui.components.UnseenDot
import app.codenvi.ui.components.color
import app.codenvi.ui.components.label
import app.codenvi.ui.theme.MonoStyle
import app.codenvi.ui.theme.Tokens
import java.text.DateFormat
import java.util.Date

/** Project (docs/design.md §10.3 screen 4): Sessions, Memory (CLAUDE.md), Commits. */
@Composable
fun ProjectScreen(vm: AppViewModel, projectId: String, onBack: () -> Unit, onChat: (String) -> Unit) {
    val project by remember(projectId) { vm.repo.project(projectId) }.collectAsStateWithLifecycle(null)
    var tab by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) } // "New session" dialog, opened by the + button in the top bar
    // CLAUDE.md state lives here so the Save and Reload buttons can sit in the top bar.
    var memory by remember { mutableStateOf("") }
    var memoryLoaded by remember { mutableStateOf(false) }
    var memoryBusy by remember { mutableStateOf(false) }
    fun loadMemory() {
        memoryBusy = true
        vm.run(onOk = { memoryLoaded = true }) { try { memory = vm.repo.readMemory(projectId) } finally { memoryBusy = false } }
    }
    fun saveMemory() {
        memoryBusy = true
        vm.run(onOk = { vm.ok("CLAUDE.md saved on the PC.") }) { try { vm.repo.writeMemory(projectId, memory) } finally { memoryBusy = false } }
    }
    // Entering the Memory tab loads the file from the PC (as before).
    LaunchedEffect(projectId, tab) { if (tab == 1) loadMemory() else memoryLoaded = false }

    Column(Modifier.fillMaxSize()) {
        CodenviTopBar(project?.name ?: "Project", onBack) {
            if (tab == 0) IconButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, "New session") }
            if (tab == 1) {
                IconButton(enabled = !memoryBusy, onClick = { loadMemory() }) { Icon(Icons.Filled.Refresh, "Reload") }
                IconButton(enabled = memoryLoaded && !memoryBusy, onClick = { saveMemory() }) { Icon(SaveIcon, "Save") }
            }
        }
        project?.let { p ->
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip(p.state.label(), p.state.color())
                Text("${p.deviceName}${if (p.deviceOnline) "" else " · offline"}", color = Tokens.OnSurfaceMuted)
            }
        }
        TabRow(
            selectedTabIndex = tab, containerColor = Tokens.Background, contentColor = Tokens.OnSurface,
            indicator = { pos -> TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(pos[tab]), color = Tokens.Primary) },
        ) {
            listOf("Sessions", "Memory", "Commits").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) },
                    selectedContentColor = Tokens.OnSurface, unselectedContentColor = Tokens.OnSurfaceMuted)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
        when (tab) {
            0 -> SessionsTab(vm, projectId, project?.defaultSessionId, onChat, creating, onCreateDismiss = { creating = false })
            1 -> MemoryTab(memory, onChange = { memory = it }, enabled = memoryLoaded && !memoryBusy)
            else -> CommitsTab(vm, projectId)
        }
        }
    }
}

@Composable
private fun SessionsTab(
    vm: AppViewModel, projectId: String, defaultSessionId: String?, onChat: (String) -> Unit,
    creating: Boolean, onCreateDismiss: () -> Unit,
) {
    val sessions by remember(projectId) { vm.repo.sessions(projectId) }.collectAsStateWithLifecycle(emptyList())
    val unseenSessions by vm.repo.unseenSessions.collectAsStateWithLifecycle(emptySet())
    var rename by remember { mutableStateOf<SessionUi?>(null) }
    var clear by remember { mutableStateOf<SessionUi?>(null) }
    var delete by remember { mutableStateOf<SessionUi?>(null) }
    LaunchedEffect(projectId) { vm.run { vm.repo.refreshSessions(projectId) } }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sessions, key = { it.id }) { s ->
                var menu by remember { mutableStateOf(false) }
                Card(colors = CardDefaults.cardColors(containerColor = Tokens.Surface),
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !s.locked) { onChat(s.id) }) {
                    Row(Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(s.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (s.id == defaultSessionId) Icon(Icons.Filled.Star, "Default session", tint = Tokens.Primary)
                        // White dot just before the 3-dot button: this session has a reply you have not seen yet.
                        if (s.id in unseenSessions) { UnseenDot(); Spacer(Modifier.width(4.dp)) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Use for Multimode") }, onClick = { menu = false; vm.run { vm.repo.setDefaultSession(projectId, s.id) } })
                                DropdownMenuItem(text = { Text("Rename") }, enabled = !s.locked, onClick = { menu = false; rename = s })
                                DropdownMenuItem(text = { Text("Clear memory") }, onClick = { menu = false; clear = s })
                                DropdownMenuItem(text = { Text("Delete", color = Tokens.Error) }, onClick = { menu = false; delete = s })
                            }
                        }
                    }
                }
            }
        }
    }
    if (creating) InputDialog("New session", "Session ${sessions.size + 1}", "Create",
        onConfirm = { n -> vm.run { onChat(vm.repo.createSession(projectId, n)) } }, onDismiss = onCreateDismiss)
    rename?.let { s -> InputDialog("Rename session", s.name, onConfirm = { n -> vm.run { vm.repo.renameSession(s.id, n) } }, onDismiss = { rename = null }) }
    clear?.let { s ->
        ConfirmDialog("Clear memory", "Clear the chat of \"${s.name}\" and start Claude with empty context on the next prompt?", "Clear",
            onConfirm = { vm.run(onOk = { vm.ok("Memory cleared.") }) { vm.repo.clearMemory(s.id) } }, onDismiss = { clear = null })
    }
    delete?.let { s ->
        ConfirmDialog("Delete session", "Delete \"${s.name}\" and its chat history?", "Delete",
            onConfirm = { vm.run { vm.repo.deleteSession(s.id) } }, onDismiss = { delete = null }, destructive = true)
    }
}

/** CLAUDE.md editor. Save and Reload are the icon buttons in the top bar. */
@Composable
private fun MemoryTab(text: String, onChange: (String) -> Unit, enabled: Boolean) {
    val dark = Color(0xFF0A0A0B) // a little darker than the page background
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("CLAUDE.md in the project folder - instructions Claude Code reads on every run.", color = Tokens.OnSurfaceMuted)
        OutlinedTextField(
            value = text, onValueChange = onChange, textStyle = MonoStyle, enabled = enabled,
            modifier = Modifier.fillMaxWidth().weight(1f),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = dark, unfocusedContainerColor = dark, disabledContainerColor = dark,
                disabledTextColor = Tokens.OnSurfaceMuted,
            ),
        )
    }
}

@Composable
private fun CommitsTab(vm: AppViewModel, projectId: String) {
    val commits by remember(projectId) { vm.repo.commits(projectId) }.collectAsStateWithLifecycle(emptyList())
    var rollback by remember { mutableStateOf<CommitUi?>(null) }
    LaunchedEffect(projectId) { vm.run { vm.repo.refreshCommits(projectId) } }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (commits.isEmpty()) item { Text("No commits yet.", color = Tokens.OnSurfaceMuted) }
        items(commits, key = { it.id }) { c ->
            Card(colors = CardDefaults.cardColors(containerColor = Tokens.Surface), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(c.gitHash.take(7), style = MonoStyle, color = Tokens.OnPrimaryContainer)
                        Text(fmt.format(Date(c.timeMs)), color = Tokens.OnSurfaceMuted, style = MaterialTheme.typography.labelSmall)
                        if (c.reverted) Chip("Reverted", Tokens.Error)
                        if (c.kind == "rollback") Chip("Rollback", Tokens.Warning)
                        if (c.kind == "baseline") Chip("Baseline", Tokens.OnSurfaceMuted)
                    }
                    Text(c.message)
                    Text("${c.files} files · +${c.insertions} −${c.deletions}", color = Tokens.OnSurfaceMuted, style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { rollback = c }) { Text("Roll back to here") }
                }
            }
        }
    }
    rollback?.let { c ->
        ConfirmDialog("Roll back", "Roll back to ${c.gitHash.take(7)}?\n\nEvery later commit is reverted in one new commit on the PC. History is kept.",
            "Roll back", onConfirm = { vm.run(onOk = { vm.ok("Rolled back.") }) { vm.repo.rollback(projectId, c.gitHash) } },
            onDismiss = { rollback = null }, destructive = true)
    }
}
