package app.codenvi.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.codenvi.ui.theme.Tokens

/** Dictation button: a plain dot, muted when idle and a pulsing red while recording. */
@Composable
fun RecordButton(recording: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, transcribing: Boolean = false) {
    val pulse = rememberInfiniteTransition(label = "record")
    val scale by pulse.animateFloat(
        initialValue = 1f, targetValue = 1.35f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "scale",
    )
    val alpha by pulse.animateFloat(
        initialValue = 1f, targetValue = 0.45f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "alpha",
    )
    IconButton(enabled = !transcribing, onClick = onClick,
        modifier = modifier.semantics { contentDescription = if (transcribing) "Transcribing" else if (recording) "Stop dictation" else "Start dictation" }) {
        // After the stop tap the recording is being turned into text: a small spinner replaces the dot.
        if (transcribing) { CircularProgressIndicator(Modifier.size(18.dp), color = Tokens.Primary, strokeWidth = 2.dp); return@IconButton }
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(14.dp)
                    .then(if (recording) Modifier.scale(scale).alpha(alpha) else Modifier)
                    .clip(CircleShape)
                    .background(if (recording) Tokens.Error else Tokens.OnSurfaceMuted),
            )
        }
    }
}
