package app.codenvi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codenvi.CodenviApp
import app.codenvi.data.AppSettings
import app.codenvi.net.JobStatuses
import app.codenvi.repo.ChatItem
import app.codenvi.ui.AppViewModel
import app.codenvi.ui.components.MarkdownText
import app.codenvi.ui.components.ModelEffortPicker
import app.codenvi.ui.components.rememberDictation
import app.codenvi.ui.components.rememberMicPermission
import app.codenvi.ui.theme.MonoStyle
import app.codenvi.ui.theme.Tokens
import kotlinx.coroutines.launch

/** Chat per session (docs/design.md §10.3 screen 5). */
@Composable
fun ChatScreen(vm: AppViewModel, sessionId: String, onBack: () -> Unit) {
    val repo = vm.repo
    val session by remember(sessionId) { repo.session(sessionId) }.collectAsStateWithLifecycle(null)
    val chatItems by remember(sessionId) { repo.chat(sessionId) }.collectAsStateWithLifecycle(emptyList())
    val active by remember(sessionId) { repo.activeJobs(sessionId) }.collectAsStateWithLifecycle(emptyList())
    val progress by repo.progress.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(sessionId) { vm.run { repo.syncMessages(sessionId) } }
    LaunchedEffect(chatItems.size) { if (chatItems.isNotEmpty()) listState.animateScrollToItem(chatItems.lastIndex) }

    Column(Modifier.fillMaxSize().imePadding()) {
        CodenviTopBar(session?.name ?: "Chat", onBack)
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(chatItems, key = { it.id }) { ChatBubble(vm, it) }
        }
        val running = active.firstOrNull { it.status == JobStatuses.RUNNING } ?: active.firstOrNull()
        if (running != null) WorkingCard(
            status = running.status, lines = progress[running.id] ?: emptyList(),
            onStop = { vm.run { repo.cancel(running.id) } },
        )
        PromptBar(vm, onSend = { text, model, effort -> repo.sendPrompt(sessionId, text, model, effort) })
    }
}

@Composable
fun ChatBubble(vm: AppViewModel, item: ChatItem) {
    val context = LocalContext.current
    val readAloud = remember { (context.applicationContext as CodenviApp).container.readAloud }
    val playing by readAloud.playing.collectAsStateWithLifecycle()
    val settings by vm.repo.settings.flow.collectAsStateWithLifecycle(AppSettings())
    val mine = item.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 340.dp)
                .background(if (mine) Tokens.PrimaryContainer else Tokens.SurfaceVariant, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (mine) {
                SelectionContainer { Text(item.text, color = Tokens.OnPrimaryContainer) }
                StatusLine(item.status, item.error)
            } else {
                SelectionContainer { MarkdownText(item.text) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val meta = listOfNotNull(item.commitHash?.take(7)?.let { "commit $it" }, item.costUsd?.let { "$" + "%.4f".format(it) })
                    Text(meta.joinToString(" · "), color = Tokens.OnSurfaceMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        readAloud.configure(settings.ttsSpeed, settings.ttsVoice)
                        readAloud.toggle(item.id, item.text)
                    }) {
                        if (playing == item.id) Icon(Icons.Filled.Close, "Stop reading", tint = Tokens.Primary)
                        else Text("🔊")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(status: String?, error: String?) {
    val (label, color) = when (status) {
        null -> return
        JobStatuses.QUEUED -> "Queued" to Tokens.Warning
        JobStatuses.DELIVERED -> "Delivered" to Tokens.Warning
        JobStatuses.RUNNING -> "Running…" to Tokens.Primary
        JobStatuses.SUCCEEDED -> "Done" to Tokens.Success
        JobStatuses.FAILED -> "Failed" to Tokens.Error
        JobStatuses.CANCELLED -> "Cancelled" to Tokens.OnSurfaceMuted
        JobStatuses.REJECTED_STALE -> "Rejected - too old" to Tokens.Error
        JobStatuses.WAITING_LOGIN -> "Waiting for Claude sign-in - sign in to Claude Code on that PC" to Tokens.Warning
        else -> status to Tokens.OnSurfaceMuted
    }
    Text(label, color = color, style = MaterialTheme.typography.labelSmall)
    if (status == JobStatuses.FAILED && !error.isNullOrBlank()) Text(error, color = Tokens.Error, style = MaterialTheme.typography.labelSmall)
}

/** Live "working…" card with streamed progress lines and a Stop button. */
@Composable
fun WorkingCard(status: String, lines: List<String>, onStop: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .background(Tokens.PrimaryContainer, RoundedCornerShape(12.dp)).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (status == JobStatuses.RUNNING) CircularProgressIndicator(Modifier.size(16.dp), color = Tokens.Primary, strokeWidth = 2.dp)
            Text(
                when (status) {
                    JobStatuses.RUNNING -> "  Working…"
                    JobStatuses.WAITING_LOGIN -> "Waiting for Claude sign-in on the PC"
                    else -> "Queued - waiting for the PC"
                },
                color = Tokens.OnPrimaryContainer, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onStop) { Text("Stop", color = Tokens.Error) }
        }
        lines.takeLast(4).forEach { Text(it, style = MonoStyle, color = Tokens.OnPrimaryContainer, maxLines = 1) }
    }
}

/**
 * Input bar: text field, mic, model and effort dropdowns, Send. Dictation inserts at the cursor and never sends
 * by itself (docs/design.md §10.4).
 */
@Composable
fun PromptBar(vm: AppViewModel, enabled: Boolean = true, onSend: suspend (String, String, String) -> Unit) {
    val settings by vm.repo.settings.flow.collectAsStateWithLifecycle(AppSettings())
    val catalog by vm.repo.catalog.collectAsStateWithLifecycle()
    var value by remember { mutableStateOf(TextFieldValue("")) }
    var model by remember(settings.lastModel) { mutableStateOf(settings.lastModel) }
    var effort by remember(settings.lastEffort) { mutableStateOf(settings.lastEffort) }
    var sending by remember { mutableStateOf(false) }
    val dictation = rememberDictation()
    val listening by dictation.listening.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val mic = rememberMicPermission(onDenied = { vm.say("Microphone permission is needed for dictation.") }) {
        dictation.toggle(value, settings.speechLanguage, { value = it }, { vm.say(it) })
    }

    Column(Modifier.fillMaxWidth().background(Tokens.Surface).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value, onValueChange = { value = it }, placeholder = { Text("Ask Claude Code…") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 200.dp), enabled = enabled,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(1f)) { ModelEffortPicker(catalog, model, effort) { m, e -> model = m; effort = e } }
            if (dictation.available) {
                IconButton(onClick = mic) { Text(if (listening) "⏺" else "🎙", color = if (listening) Tokens.Error else Tokens.OnSurface) }
            }
            FilledIconButton(
                enabled = enabled && !sending && value.text.isNotBlank(),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Tokens.Primary, contentColor = Tokens.OnPrimary),
                onClick = {
                    val text = value.text.trim()
                    sending = true
                    dictation.stop()
                    scope.launch {
                        try {
                            onSend(text, model, effort)
                            value = TextFieldValue("")
                        } catch (e: Exception) {
                            vm.say(app.codenvi.repo.RevokedOrUser.message(e))
                        } finally { sending = false }
                    }
                },
            ) { Icon(Icons.Filled.Send, "Send") }
        }
        if (!dictation.available) Text("Voice input needs Google speech services or an offline language pack.",
            color = Tokens.OnSurfaceMuted, style = MaterialTheme.typography.labelSmall)
    }
}
