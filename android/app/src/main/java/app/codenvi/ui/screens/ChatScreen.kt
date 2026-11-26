package app.codenvi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.style.TextOverflow
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
import app.codenvi.ui.components.ChangesIcon
import app.codenvi.ui.components.CopyIcon
import app.codenvi.ui.components.GridIcon
import app.codenvi.ui.components.PublishIcon
import app.codenvi.ui.components.PullIcon
import app.codenvi.ui.components.RecordButton
import app.codenvi.ui.components.SaveIcon
import app.codenvi.ui.components.SoundIcon
import app.codenvi.ui.components.rememberMicPermission
import app.codenvi.ui.theme.MonoStyle
import app.codenvi.ui.theme.Tokens
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/** Chat per session (docs/design.md §10.3 screen 5). */
@Composable
fun ChatScreen(vm: AppViewModel, sessionId: String, onBack: () -> Unit, onMultimode: () -> Unit, onChanges: () -> Unit = {}) {
    val repo = vm.repo
    val session by remember(sessionId) { repo.session(sessionId) }.collectAsStateWithLifecycle(null)
    val project by remember(session?.projectId) { session?.projectId?.let { repo.project(it) } ?: emptyFlow() }.collectAsStateWithLifecycle(null)
    val chatItems by remember(sessionId) { repo.chat(sessionId) }.collectAsStateWithLifecycle(emptyList())
    val active by remember(sessionId) { repo.activeJobs(sessionId) }.collectAsStateWithLifecycle(emptyList())
    val progress by repo.progress.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val git = rememberGitActions(vm, sessionId)

    LaunchedEffect(sessionId) { vm.run { repo.syncMessages(sessionId) } }
    // Everything in an open chat counts as seen (clears the red dot on the Multimode grid), including replies arriving now.
    LaunchedEffect(sessionId, chatItems.lastOrNull()?.id) { if (chatItems.isNotEmpty()) repo.markSeen(sessionId) }
    LaunchedEffect(chatItems.size) { if (chatItems.isNotEmpty()) listState.animateScrollToItem(chatItems.lastIndex) }
    // Also re-checks when a new message arrives: the PC no longer commits automatically, so the Commit button must show up after a reply.
    LaunchedEffect(project?.id, project?.deviceOnline, chatItems.lastOrNull()?.id) { project?.let { if (it.deviceOnline) git.refresh(it.id) } }

    Column(Modifier.fillMaxSize().imePadding()) {
        CodenviTopBar(session?.name ?: "Chat", onBack) {
            GitHeaderButtons(git, project)
            // Always available: opens the list of uncommitted files of this project.
            IconButton(enabled = session != null, onClick = onChanges) { Icon(ChangesIcon, "Code changes") }
            // Always available: presets Multimode to this chat's project and session, then switches to it.
            IconButton(enabled = session != null, onClick = {
                session?.let { s ->
                    vm.run(onOk = onMultimode) {
                        repo.settings.setMultimodeProject(s.projectId)
                        repo.settings.setMultimodeSession(s.id)
                    }
                }
            }) { Icon(GridIcon, "Multimode") }
        }
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

    GitActionDialogs(git, project)
}

@Composable
fun ChatBubble(vm: AppViewModel, item: ChatItem) {
    val context = LocalContext.current
    val readAloud = remember { (context.applicationContext as CodenviApp).container.readAloud }
    val playing by readAloud.playing.collectAsStateWithLifecycle()
    val settings by vm.repo.settings.flow.collectAsStateWithLifecycle(AppSettings())
    if (item.eventKind != null) { ChatEventBlock(item.eventKind, item.text); return }
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
                    // Copy icon at the bottom left: puts the whole reply (as received, Markdown included) on the clipboard.
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Codenvi reply", item.text))
                        vm.ok("Copied.")
                    }) { Icon(CopyIcon, "Copy message", tint = Tokens.OnSurfaceMuted) }
                    val meta = listOfNotNull(item.commitHash?.take(7)?.let { "commit $it" })
                    Text(meta.joinToString(" · "), color = Tokens.OnSurfaceMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        readAloud.configure(settings.ttsSpeed, settings.ttsVoice)
                        readAloud.toggle(item.id, item.text)
                    }) {
                        // Muted speaker; orange while this message is being read (tap again to stop).
                        val reading = playing == item.id
                        Icon(SoundIcon, if (reading) "Stop reading" else "Read aloud", tint = if (reading) Tokens.Primary else Tokens.OnSurfaceMuted)
                    }
                }
            }
        }
    }
}

/**
 * Small centred marker between messages: yellow for a commit, green for a push, blue for a pull (semi-transparent fill + solid border).
 * The action is shown as the same icon the chat header uses for it, followed by the one-line message. Stored labels read
 * "Committed · <message>", "Pushed · <message>" or "Pulled · <message>"; that word prefix is dropped here (also for older rows).
 */
@Composable
private fun ChatEventBlock(kind: String, label: String) {
    val color = when (kind) {
        "publish" -> Tokens.Success
        "pull" -> Color(0xFF42A5F5)
        else -> Tokens.Warning
    }
    val icon = when (kind) {
        "publish" -> PublishIcon
        "pull" -> PullIcon
        else -> SaveIcon
    }
    val prefix = when (kind) { "publish" -> "Pushed"; "pull" -> "Pulled"; else -> "Committed" }
    // "Nothing to commit" has no prefix: it stays as plain text without an icon.
    val hasAction = label == prefix || label.startsWith("$prefix · ")
    val text = if (hasAction) label.removePrefix(prefix).removePrefix(" · ") else label
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .background(color.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
                .border(1.dp, color, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            if (hasAction) Icon(icon, contentDescription = prefix, tint = color, modifier = Modifier.size(16.dp))
            if (text.isNotEmpty()) Text(
                text, color = color, style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
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
fun PromptBar(
    vm: AppViewModel, enabled: Boolean = true, header: @Composable (() -> Unit)? = null,
    /** Shown in the top right corner of the message block (Multimode puts its read-aloud button here). */
    topRight: @Composable (() -> Unit)? = null,
    /** While true the bar is locked and Send spins (a prompt for this session is still being answered); tapping Send then calls [onStop]. */
    busy: Boolean = false, onStop: (() -> Unit)? = null,
    onSend: suspend (String, String, String) -> Unit,
) {
    val settings by vm.repo.settings.flow.collectAsStateWithLifecycle(AppSettings())
    val catalog by vm.repo.catalog.collectAsStateWithLifecycle()
    var value by remember { mutableStateOf(TextFieldValue("")) }
    var model by remember(settings.lastModel) { mutableStateOf(settings.lastModel) }
    var effort by remember(settings.lastEffort) { mutableStateOf(settings.lastEffort) }
    var sending by remember { mutableStateOf(false) }
    val dictation = rememberDictation()
    val listening by dictation.listening.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val mic = rememberMicPermission(onDenied = { vm.warn("Microphone permission is needed for dictation.") }) {
        dictation.toggle(value, settings.speechLanguage, { value = it }, { vm.fail(it) })
    }

    Column(Modifier.fillMaxWidth().background(Tokens.Surface).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (header != null || topRight != null) {
            // One row, centred vertically: the session pills (taking the free width) and the top-right button on the same line.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { header?.invoke() }
                topRight?.invoke()
            }
        }
        OutlinedTextField(
            value = value, onValueChange = { value = it }, placeholder = { Text("Ask Claude Code…") },
            // X inside the field: clears the text (and stops dictation, so it does not type it back).
            trailingIcon = {
                if (value.text.isNotEmpty()) IconButton(onClick = { dictation.stop(); value = TextFieldValue("") }) {
                    Icon(Icons.Filled.Close, "Clear text", tint = Tokens.OnSurfaceMuted)
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 200.dp), enabled = enabled && !busy,
            shape = RoundedCornerShape(20.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(1f)) { ModelEffortPicker(catalog, model, effort) { m, e -> model = m; effort = e } }
            if (dictation.available) {
                RecordButton(recording = listening, onClick = { if (!busy) mic() })
            }
            // While a prompt for this session is being answered the Send button spins; tapping it then stops that job.
            FilledIconButton(
                enabled = if (busy) onStop != null else enabled && !sending && value.text.isNotBlank(),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Tokens.Primary, contentColor = Tokens.OnPrimary),
                onClick = {
                    if (busy) { onStop?.invoke(); return@FilledIconButton }
                    val text = value.text.trim()
                    sending = true
                    dictation.stop()
                    scope.launch {
                        try {
                            onSend(text, model, effort)
                            value = TextFieldValue("")
                        } catch (e: Exception) {
                            vm.fail(app.codenvi.repo.RevokedOrUser.message(e))
                        } finally { sending = false }
                    }
                },
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Tokens.OnPrimary, strokeWidth = 2.5.dp)
                else Icon(Icons.Filled.Send, "Send")
            }
        }
        if (!dictation.available) Text("Voice input needs Google speech services or an offline language pack.",
            color = Tokens.OnSurfaceMuted, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Reads the newest reply of [sessionId] aloud (tap again to stop). Same icon and colours as the speaker on a reply in the chat:
 * muted, orange while reading. Disabled until the session has a reply.
 */
@Composable
fun ReadAloudButton(vm: AppViewModel, sessionId: String?) {
    val context = LocalContext.current
    val readAloud = remember { (context.applicationContext as CodenviApp).container.readAloud }
    val playing by readAloud.playing.collectAsStateWithLifecycle()
    val settings by vm.repo.settings.flow.collectAsStateWithLifecycle(AppSettings())
    val items by remember(sessionId) { sessionId?.let { vm.repo.chat(it) } ?: emptyFlow() }.collectAsStateWithLifecycle(emptyList())
    val last = items.lastOrNull { it.role != "user" && it.eventKind == null }
    val reading = last != null && playing == last.id
    IconButton(enabled = last != null, onClick = {
        if (last != null) {
            readAloud.configure(settings.ttsSpeed, settings.ttsVoice)
            readAloud.toggle(last.id, last.text)
            // Having it read out counts as seen: clears the red dots for this session.
            if (sessionId != null) vm.run { vm.repo.markSeen(sessionId) }
        }
    }) {
        Icon(SoundIcon, if (reading) "Stop reading" else "Read last reply aloud", tint = if (reading) Tokens.Primary else Tokens.OnSurfaceMuted)
    }
}
