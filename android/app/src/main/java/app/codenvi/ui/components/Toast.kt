package app.codenvi.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.codenvi.ui.theme.Tokens
import kotlinx.coroutines.delay

enum class ToastKind(val color: Color, val seconds: Long) {
    Success(Tokens.Success, 3),
    Info(Tokens.Primary, 4),
    Warning(Tokens.Warning, 5),
    Error(Tokens.Error, 6),
}

data class Toast(val text: String, val kind: ToastKind = ToastKind.Info, val id: Long = System.nanoTime())

/**
 * A dark pop-up at the top of the app. Its border and text take the colour of the [ToastKind].
 * Tap it to dismiss; otherwise it goes away by itself.
 */
@Composable
fun ToastHost(toast: Toast?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    // Keep the last toast while it fades out.
    var shown by remember { mutableStateOf<Toast?>(null) }
    if (toast != null) shown = toast
    LaunchedEffect(toast?.id) {
        if (toast != null) {
            delay(toast.kind.seconds * 1000)
            onDismiss()
        }
    }
    Box(modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = toast != null,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            val t = shown
            if (t != null) {
                Surface(
                    color = Tokens.Surface,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    border = BorderStroke(1.5.dp, t.kind.color),
                    shadowElevation = 6.dp,
                    modifier = Modifier.fillMaxWidth().clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss,
                    ),
                ) {
                    Text(t.text, color = t.kind.color, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                }
            }
        }
    }
}
