package app.codenvi.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codenvi.BuildConfig
import app.codenvi.CodenviApp
import app.codenvi.R
import app.codenvi.data.AppSettings
import app.codenvi.net.InstallDto
import app.codenvi.ui.AppViewModel
import app.codenvi.ui.components.ConfirmDialog
import app.codenvi.ui.components.InputDialog
import app.codenvi.ui.components.Picker
import app.codenvi.ui.theme.Tokens
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Settings (Project.md §10.3 screen 7). */
@Composable
fun SettingsScreen(vm: AppViewModel, onDisconnected: () -> Unit) {
    val repo = vm.repo
    val context = LocalContext.current
    val secure by repo.secure.collectAsStateWithLifecycle()
    val settings by repo.settings.flow.collectAsStateWithLifecycle(AppSettings())
    var installs by remember { mutableStateOf<List<InstallDto>>(emptyList()) }
    var revoke by remember { mutableStateOf<InstallDto?>(null) }
    var rename by remember { mutableStateOf<InstallDto?>(null) }
    var disconnect by remember { mutableStateOf(false) }
    var language by remember(settings.speechLanguage) { mutableStateOf(settings.speechLanguage ?: "") }
    val scope = rememberCoroutineScope()
    val readAloud = remember { (context.applicationContext as CodenviApp).container.readAloud }
    val voices = remember { readAloud.voices() }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    fun loadInstalls() = vm.run { installs = repo.installs() }
    LaunchedEffect(Unit) { loadInstalls() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

        Section("Server") {
            Text(secure.serverHost ?: "Not connected")
            if (secure.pin != null) Text("Pinned key (self-signed server)", color = Tokens.OnSurfaceMuted)
        }

        Section("Paired installs") {
            installs.forEach { i ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(i.name + if (i.isSelf) " (this phone)" else "")
                        Text("${i.kind} · ${i.appVersion}+${i.appBuild} · ${fmt.format(Date(i.lastSeenAtMs))}${if (i.revoked) " · revoked" else ""}",
                            color = Tokens.OnSurfaceMuted, style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { rename = i }) { Text("Rename") }
                    if (!i.revoked && !i.isSelf) TextButton(onClick = { revoke = i }) { Text("Revoke", color = Tokens.Error) }
                }
                HorizontalDivider(color = Tokens.Outline)
            }
            OutlinedButton(onClick = { loadInstalls() }) { Text("Refresh") }
        }

        Section("Read aloud") {
            Text("Speed ${"%.1f".format(settings.ttsSpeed)}×")
            Slider(value = settings.ttsSpeed, onValueChange = { v -> scope.launch { repo.settings.setTtsSpeed(v) } }, valueRange = 0.5f..2.0f,
                colors = SliderDefaults.colors(thumbColor = Tokens.Primary, activeTrackColor = Tokens.Primary))
            if (voices.isNotEmpty()) Picker("Voice", settings.ttsVoice ?: "", listOf("" to "System default") + voices.map { it to it },
                { v -> scope.launch { repo.settings.setTtsVoice(v.ifEmpty { null }) } })
        }

        Section("Speech input") {
            OutlinedTextField(value = language, onValueChange = { language = it }, singleLine = true,
                label = { Text("Language (e.g. en-US, de-DE; empty = phone default)") }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { scope.launch { repo.settings.setSpeechLanguage(language.trim()) }; vm.say("Saved.") }) { Text("Save language") }
        }

        Section("This phone") {
            OutlinedButton(onClick = { disconnect = true }) { Text("Disconnect this phone", color = Tokens.Error) }
            Text("Revokes this phone on the server and deletes its tokens, keys and cached chats.", color = Tokens.OnSurfaceMuted,
                style = MaterialTheme.typography.labelSmall)
        }

        Section("About") {
            Text("Codenvi ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
            Text(stringResource(R.string.disclaimer), color = Tokens.OnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.license_notice))
            val url = stringResource(R.string.repository_url)
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) { Text(url, color = Tokens.Primary) }
            Text("Third-party notices", style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.third_party), color = Tokens.OnSurfaceMuted, style = MaterialTheme.typography.labelSmall)
        }
    }

    revoke?.let { i ->
        ConfirmDialog("Revoke", "Revoke \"${i.name}\"? It is disconnected immediately and must be paired again.", "Revoke",
            onConfirm = { vm.run(onOk = { loadInstalls() }) { repo.revokeInstall(i.id) } }, onDismiss = { revoke = null }, destructive = true)
    }
    rename?.let { i -> InputDialog("Rename", i.name, onConfirm = { n -> vm.run(onOk = { loadInstalls() }) { repo.renameInstall(i.id, n) } }, onDismiss = { rename = null }) }
    if (disconnect) ConfirmDialog("Disconnect this phone", "Revoke this phone and delete its tokens, keys and cached chats? You can pair again with Connect a phone on a PC.",
        "Disconnect", onConfirm = { vm.run(onOk = onDisconnected) { repo.disconnect() } }, onDismiss = { disconnect = false }, destructive = true)
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Tokens.Surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Tokens.OnSurface)
            content()
        }
    }
}
