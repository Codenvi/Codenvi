package app.codenvi.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.codenvi.net.ModelCatalogEntry
import app.codenvi.repo.ProjectState
import app.codenvi.speech.SpeechInput
import app.codenvi.ui.theme.Tokens

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) =
    Box(modifier.size(9.dp).background(color, CircleShape))

fun ProjectState.color(): Color = when (this) {
    ProjectState.RUNNING -> Tokens.Primary
    ProjectState.QUEUED, ProjectState.WAITING_LOGIN -> Tokens.Warning
    ProjectState.IDLE -> Tokens.OnSurfaceMuted
}

fun ProjectState.label(): String = when (this) {
    ProjectState.RUNNING -> "Running"
    ProjectState.QUEUED -> "Queued"
    ProjectState.WAITING_LOGIN -> "Waiting for Claude sign-in"
    ProjectState.IDLE -> "Idle"
}

/** Status chip: running = orange, queued = amber, so the two are never confused (docs/design.md §0.7). */
@Composable
fun Chip(text: String, color: Color) {
    Row(
        Modifier.background(Tokens.SurfaceVariant, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        StatusDot(color, Modifier.size(7.dp))
        Text(text, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = Tokens.OnSurface)
    }
}

@Composable
fun Picker(value: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit, modifier: Modifier = Modifier, label: String? = null) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        FilledTonalButton(onClick = { open = true }) {
            val shown = options.firstOrNull { it.first == value }?.second ?: value
            Text(if (label == null) shown else "$label: $shown", maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (k, v) ->
                DropdownMenuItem(text = { Text(v) }, onClick = { onSelect(k); open = false })
            }
        }
    }
}

/** Model and effort dropdowns (contents from /catalog/models, editable on the server). */
@Composable
fun ModelEffortPicker(catalog: List<ModelCatalogEntry>, model: String, effort: String, onChange: (String, String) -> Unit) {
    val models = catalog.map { it.alias to it.label }.ifEmpty { listOf("default" to "Default (PC setting)") }
    val efforts = listOf("default" to "Default (PC setting)") +
        (catalog.firstOrNull { it.alias == model }?.efforts ?: listOf("low", "medium", "high")).map { it to it.replaceFirstChar(Char::uppercase) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Picker(model, models, { m -> onChange(m, if (efforts.any { it.first == effort }) effort else "default") })
        Picker(effort, efforts, { e -> onChange(model, e) })
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, destructive: Boolean = false) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm, color = if (destructive) Tokens.Error else Tokens.Primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun InputDialog(title: String, initial: String, confirm: String = "Save", singleLine: Boolean = true, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = singleLine) },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Voice dictation bound to a TextFieldValue (docs/design.md §10.4): partial results show live in the field,
 * the final text is inserted at the cursor, and nothing is ever sent automatically.
 */
class Dictation(private val speech: SpeechInput) {
    private var base: TextFieldValue? = null

    val listening get() = speech.listening
    val available get() = speech.isAvailable

    fun toggle(current: TextFieldValue, language: String?, update: (TextFieldValue) -> Unit, onError: (String) -> Unit) {
        if (speech.listening.value) { speech.stop(); return }
        base = current
        speech.start(language,
            onPartial = { update(insert(base ?: current, it, moveCursor = false)) },
            onFinal = { text ->
                val b = base ?: current
                update(if (text.isBlank()) b else insert(b, text, moveCursor = true))
                base = null
            },
            onError = { msg -> base?.let(update); base = null; onError(msg) },
        )
    }

    fun stop() = speech.stop()

    companion object {
        fun insert(value: TextFieldValue, text: String, moveCursor: Boolean): TextFieldValue {
            val start = value.selection.min.coerceIn(0, value.text.length)
            val end = value.selection.max.coerceIn(0, value.text.length)
            val before = value.text.substring(0, start)
            val after = value.text.substring(end)
            val sepBefore = if (before.isNotEmpty() && !before.endsWith(" ") && !before.endsWith("\n")) " " else ""
            val sepAfter = if (after.isNotEmpty() && !after.startsWith(" ")) " " else ""
            val inserted = sepBefore + text + sepAfter
            val cursor = if (moveCursor) start + sepBefore.length + text.length else start
            return TextFieldValue(before + inserted + after, TextRange(cursor))
        }
    }
}

@Composable
fun rememberDictation(): Dictation {
    val context = LocalContext.current
    val speech = remember { SpeechInput(context) }
    DisposableEffect(Unit) { onDispose { speech.stop() } }
    return remember { Dictation(speech) }
}

/** Requests RECORD_AUDIO on the first mic tap, then runs [action]. */
@Composable
fun rememberMicPermission(onDenied: () -> Unit, action: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) action() else onDenied()
    }
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) action()
        else launcher.launch(Manifest.permission.RECORD_AUDIO)
    }
}
