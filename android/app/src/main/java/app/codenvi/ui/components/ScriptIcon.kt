package app.codenvi.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The Material "code" (angle brackets) icon. It lives in material-icons-extended, which is a very large dependency,
 * so the single path is defined here instead.
 */
val ScriptIcon: ImageVector by lazy {
    ImageVector.Builder(name = "Script", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            PathParser().parsePathString(
                "M9.4,16.6L4.8,12l4.6,-4.6L8,6l-6,6 6,6 1.4,-1.4zM14.6,16.6l4.6,-4.6 -4.6,-4.6L16,6l6,6 -6,6 -1.4,-1.4z"
            ).toNodes(),
            fill = SolidColor(Color.Black),
        )
        .build()
}
