package app.codenvi.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** The Material "stop" icon (a filled square), defined here to avoid the large material-icons-extended dependency. */
val StopIcon: ImageVector by lazy {
    ImageVector.Builder(name = "Stop", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(PathParser().parsePathString("M6,6h12v12H6z").toNodes(), fill = SolidColor(Color.Black))
        .build()
}
