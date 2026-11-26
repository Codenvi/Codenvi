package app.codenvi.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codenvi.MainActivity
import app.codenvi.repo.DeviceUi
import app.codenvi.repo.ProjectUi
import app.codenvi.ui.AppViewModel
import app.codenvi.ui.components.Chip
import app.codenvi.ui.components.ConfirmDialog
import app.codenvi.ui.components.CountBadge
import app.codenvi.ui.components.InputDialog
import app.codenvi.ui.components.ScriptIcon
import app.codenvi.ui.components.StopIcon
import app.codenvi.ui.components.StatusDot
import app.codenvi.ui.components.color
import app.codenvi.ui.components.label
import app.codenvi.ui.components.scanQr
import app.codenvi.ui.theme.MonoStyle
import app.codenvi.ui.theme.Tokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodenviTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1) },
        navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") } },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Tokens.Background, titleContentColor = Tokens.OnSurface),
    )
}

/** Devices (docs/design.md §10.3 screen 2): all paired PCs, online dot, "key needed" badge, add/change key. */
@Composable
fun DevicesScreen(vm: AppViewModel, activity: MainActivity, onOpen: (String) -> Unit) {
    val devices by vm.repo.devices.collectAsStateWithLifecycle(emptyList())
    val unseenByDevice by vm.repo.unseenCountByDevice.collectAsStateWithLifecycle(emptyMap())
    val runningDevices by vm.repo.runningDevices.collectAsStateWithLifecycle(emptySet())
    val connected by vm.repo.connected.collectAsStateWithLifecycle()
    var keyFor by remember { mutableStateOf<DeviceUi?>(null) }
    var keyString by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<DeviceUi?>(null) }
    var remove by remember { mutableStateOf<DeviceUi?>(null) }

    LaunchedEffect(Unit) { vm.run { vm.repo.refreshDevices() } }

    Column(Modifier.fillMaxSize()) {
        CodenviTopBar("Devices") {
            IconButton(onClick = { keyString = true }) { Icon(Icons.Filled.Add, "Add key string") }
            IconButton(onClick = { vm.run { vm.repo.refreshDevices() } }) { Icon(Icons.Filled.Refresh, "Refresh") }
        }
        if (!connected) Text("Offline - reconnecting…", color = Tokens.Warning, modifier = Modifier.padding(horizontal = 16.dp))
        if (devices.isEmpty()) {
            Text("No PCs yet. Install Codenvi.exe on a PC, connect it to your server, then use Connect a phone.",
                color = Tokens.OnSurfaceMuted, modifier = Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(devices, key = { it.id }) { d ->
                var menu by remember { mutableStateOf(false) }
                Card(
                    colors = CardDefaults.cardColors(containerColor = Tokens.Surface),
                    modifier = Modifier.fillMaxWidth().clickable {
                        if (d.hasCurrentKey) onOpen(d.id) else keyFor = d
                    },
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(if (d.online) Tokens.Success else Tokens.OnSurfaceMuted)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.name, style = MaterialTheme.typography.titleMedium)
                            Text(if (d.online) "Online" else "Offline", color = Tokens.OnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                            if (d.id in runningDevices) Chip("Running", Tokens.Primary)
                            if (!d.hasCurrentKey) Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Lock, null, tint = Tokens.Warning, modifier = Modifier.padding(end = 4.dp))
                                Text(if (d.hasKey) "New key needed (key was rotated)" else "Key needed - tap to add", color = Tokens.Warning)
                            }
                        }
                        // Number of unseen replies on this PC, just before the 3-dot button.
                        unseenByDevice[d.id]?.takeIf { it > 0 }?.let { CountBadge(it); Spacer(Modifier.width(4.dp)) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(if (d.hasCurrentKey) "Change key" else "Add key") }, onClick = { menu = false; keyFor = d })
                                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; rename = d })
                                DropdownMenuItem(text = { Text("Remove device", color = Tokens.Error) }, onClick = { menu = false; remove = d })
                            }
                        }
                    }
                }
            }
        }
    }

    keyFor?.let { d -> KeyDialog(activity, "Key for ${d.name}", onDismiss = { keyFor = null }, onMessage = { vm.fail(it) }) { text -> vm.run(onOk = { vm.ok("Key saved.") }) { vm.repo.addKey(d.id, text) } } }
    if (keyString) KeyDialog(activity, "Paste a key string", onDismiss = { keyString = false }, onMessage = { vm.fail(it) }) { text -> vm.run(onOk = { vm.ok("Key saved.") }) { vm.repo.addKeyString(text) } }
    rename?.let { d -> InputDialog("Rename PC", d.name, onConfirm = { n -> vm.run { vm.repo.renameDevice(d.id, n) } }, onDismiss = { rename = null }) }
    remove?.let { d ->
        ConfirmDialog("Remove device", "Remove ${d.name} and all its projects from the server? Nothing on the PC is deleted.", "Remove",
            onConfirm = { vm.run { vm.repo.removeDevice(d.id) } }, onDismiss = { remove = null }, destructive = true)
    }
}

/** Key entry: FLAG_SECURE while visible; KCV checked by the repository (docs/design.md §5.2). */
@Composable
fun KeyDialog(activity: MainActivity, title: String, onDismiss: () -> Unit, onMessage: (String) -> Unit = {}, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    DisposableEffect(Unit) { activity.setSecure(true); onDispose { activity.setSecure(false) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Type the key shown by Show encryption key on the PC (dashes optional), or paste a codenvi://key string.",
                    color = Tokens.OnSurfaceMuted)
                OutlinedTextField(value = text, onValueChange = { text = it }, textStyle = MonoStyle, minLines = 2)
                TextButton(onClick = { scanQr(activity, onResult = { scanned -> text = scanned }, onError = onMessage) }) { Text("Scan QR code") }
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onSave(text); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Device → Projects (screen 3). Projects are created on the desktop only. */
@Composable
fun ProjectsScreen(vm: AppViewModel, deviceId: String, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val projects by remember(deviceId) { vm.repo.projects(deviceId) }.collectAsStateWithLifecycle(emptyList())
    val unseenByProject by vm.repo.unseenCountByProject.collectAsStateWithLifecycle(emptyMap())
    val devices by vm.repo.devices.collectAsStateWithLifecycle(emptyList())
    var rename by remember { mutableStateOf<ProjectUi?>(null) }
    var runScript by remember { mutableStateOf<ProjectUi?>(null) }
    var stopScript by remember { mutableStateOf<ProjectUi?>(null) }
    LaunchedEffect(deviceId) { vm.run { vm.repo.refreshProjects(deviceId) } }

    Column(Modifier.fillMaxSize()) {
        CodenviTopBar(devices.firstOrNull { it.id == deviceId }?.name ?: "Projects", onBack) {
            IconButton(onClick = { vm.run { vm.repo.refreshProjects(deviceId) } }) { Icon(Icons.Filled.Refresh, "Refresh") }
        }
        if (projects.isEmpty()) Text("No projects. Register a project folder in Codenvi on the PC.", color = Tokens.OnSurfaceMuted,
            modifier = Modifier.padding(16.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(projects, key = { it.id }) { p ->
                var menu by remember { mutableStateOf(false) }
                Card(colors = CardDefaults.cardColors(containerColor = Tokens.Surface),
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !p.locked) { onOpen(p.id) }) {
                    Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Chip(p.state.label(), p.state.color())
                                if (p.devBranch.isNotEmpty()) Text(p.devBranch, style = MonoStyle, color = Tokens.OnSurfaceMuted)
                            }
                        }
                        if (p.hasScript && !p.locked) {
                            if (p.scriptRunning) {
                                IconButton(enabled = p.deviceOnline, onClick = { stopScript = p }) { Icon(StopIcon, "Stop script on the PC") }
                            } else {
                                IconButton(enabled = p.deviceOnline, onClick = { runScript = p }) { Icon(ScriptIcon, "Run script on the PC") }
                            }
                        }
                        unseenByProject[p.id]?.takeIf { it > 0 }?.let { CountBadge(it); Spacer(Modifier.width(4.dp)) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Rename") }, enabled = !p.locked, onClick = { menu = false; rename = p })
                            }
                        }
                    }
                }
            }
        }
    }
    runScript?.let { p ->
        ConfirmDialog("Run script", "Run the script of \"${p.name}\" on ${p.deviceName}?", "Run",
            onConfirm = { vm.run(onOk = { vm.ok("Script started on the PC.") }) { vm.repo.runScript(p.id) } },
            onDismiss = { runScript = null })
    }
    stopScript?.let { p ->
        ConfirmDialog("Stop script", "Stop the running script of \"${p.name}\" on ${p.deviceName}?", "Stop",
            onConfirm = { vm.run(onOk = { vm.ok("Script stopped.") }) { vm.repo.stopScript(p.id) } },
            onDismiss = { stopScript = null })
    }
    rename?.let { p -> InputDialog("Rename project", p.name, onConfirm = { n -> vm.run { vm.repo.renameProject(p.id, n) } }, onDismiss = { rename = null }) }
}
