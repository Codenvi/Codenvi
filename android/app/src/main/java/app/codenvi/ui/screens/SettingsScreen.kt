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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codenvi.BuildConfig
import app.codenvi.CodenviApp
import app.codenvi.speech.WhisperModel
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

private const val PREVIEW_ID = "voice-preview"

/** Primary dictation languages (BCP-47 tags); "" means the phone's default. */
private val SPEECH_LANGUAGES = listOf(
    "" to "Phone default",
    "en-US" to "English (US)", "en-GB" to "English (UK)",
    "de-DE" to "German", "fr-FR" to "French", "es-ES" to "Spanish", "it-IT" to "Italian",
    "pt-BR" to "Portuguese (Brazil)", "nl-NL" to "Dutch", "pl-PL" to "Polish",
    "ru-RU" to "Russian", "uk-UA" to "Ukrainian", "ka-GE" to "Georgian", "tr-TR" to "Turkish",
    "ar-SA" to "Arabic", "hi-IN" to "Hindi",
    "ja-JP" to "Japanese", "ko-KR" to "Korean", "zh-CN" to "Chinese (Mandarin)",
)

/** Settings (docs/design.md §10.3 screen 7). */
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
    val scope = rememberCoroutineScope()
    val readAloud = remember { (context.applicationContext as CodenviApp).container.readAloud }
    val voices = remember { readAloud.voices() }
    val playingId by readAloud.playing.collectAsStateWithLifecycle()
    val previewing = playingId == PREVIEW_ID
    DisposableEffect(Unit) { onDispose { if (readAloud.playing.value == PREVIEW_ID) readAloud.stop() } }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (voices.isNotEmpty()) Picker(settings.ttsVoice ?: "", listOf("" to "System default") + voices.map { it to it },
                    { v -> scope.launch { repo.settings.setTtsVoice(v) } }, label = "Voice")
                Spacer(Modifier.weight(1f))
                // Preview: speaks a short sentence with the selected voice and speed; tap again to stop.
                IconButton(onClick = {
                    readAloud.configure(settings.ttsSpeed, settings.ttsVoice)
                    readAloud.toggle(PREVIEW_ID, "This is how Codenvi will read replies aloud.")
                }) {
                    if (previewing) Box(Modifier.size(14.dp).background(Tokens.Primary, RoundedCornerShape(2.dp)))
                    else Icon(Icons.Filled.PlayArrow, "Preview voice", tint = Tokens.Primary)
                }
            }
        }

        Section("Reply sound and notifications") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Announce new replies", Modifier.weight(1f))
                Switch(checked = settings.notifyReplies, onCheckedChange = { on -> scope.launch { repo.settings.setNotifyReplies(on) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = Tokens.Primary))
            }
            Text("Plays a short chime when a reply arrives, on any page. When Codenvi is in the background it shows a pop-up with the " +
                "project name, session name and a preview of the reply (tap it to open the chat). To make that work Codenvi keeps its " +
                "connection open with a small quiet notification.", color = Tokens.OnSurfaceMuted, style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = {
                context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
            }) { Text("Android notification settings", color = Tokens.Primary) }
        }

        Section("Voice transcription (offline)") {
            val whisper = remember { (context.applicationContext as CodenviApp).container.whisper }
            val download by whisper.download.collectAsStateWithLifecycle()
            val changes by whisper.changes.collectAsStateWithLifecycle()
            val model = WhisperModel.fromId(settings.whisperModel)
            val installed = remember(model, changes, download) { whisper.installed(model) }
            if (!whisper.available) {
                Text("Offline transcription needs a 64-bit ARM phone. This phone uses Android's own speech recognizer instead.",
                    color = Tokens.OnSurfaceMuted, style = MaterialTheme.typography.labelSmall)
            } else {
                Picker(model.id, WhisperModel.values().map { it.id to it.label }, { v -> scope.launch { repo.settings.setWhisperModel(v) } }, label = "Model")
                val d = download
                when {
                    d != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Tokens.Primary, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Downloading ${d.model.id}: ${d.percent}%")
                    }
                    installed -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Downloaded - ready to use", Modifier.weight(1f), color = Tokens.Success)
                        TextButton(onClick = { whisper.delete(model) }) { Text("Delete", color = Tokens.Error) }
                    }
                    else -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Not downloaded (${model.megabytes} MB)", Modifier.weight(1f), color = Tokens.Warning)
                        OutlinedButton(onClick = { vm.run(onOk = { vm.ok("Voice model ready.") }) { whisper.download(model) } }) { Text("Download") }
                    }
                }
                Text("Tap the microphone in a chat to record, tap again to stop, and the recording is turned into text on this phone (English). " +
                    "Nothing is sent anywhere. The model is downloaded once from huggingface.co; Small is more accurate but slower.",
                    color = Tokens.OnSurfaceMuted, style = MaterialTheme.typography.labelSmall)
            }
        }

        Section("Speech input") {
            // The tag is passed to the phone's speech recognizer; a value saved earlier that is not in the list is still shown.
            Picker(settings.speechLanguage ?: "", SPEECH_LANGUAGES, { v -> scope.launch { repo.settings.setSpeechLanguage(v) } }, label = "Language")
            Text("Used when you dictate a message. Phone default follows the phone's language.", color = Tokens.OnSurfaceMuted,
                style = MaterialTheme.typography.labelSmall)
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
            val site = stringResource(R.string.website_url)
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(site))) }) { Text(site, color = Tokens.Primary) }
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
