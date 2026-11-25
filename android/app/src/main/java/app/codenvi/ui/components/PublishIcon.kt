package app.codenvi.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * An upward arrow over a baseline, used for "merge the dev branch into main and push", drawn as two simple
 * paths because material-icons-extended is a very large dependency.
 */
val PublishIcon: ImageVector by lazy {
    ImageVector.Builder(name = "Publish", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            PathParser().parsePathString("M12,3L7,8h3v6h4V8h3z").toNodes(),
            fill = SolidColor(Color.Black),
        )
        .addPath(
            PathParser().parsePathString("M5,18h14v2H5z").toNodes(),
            fill = SolidColor(Color.Black),
        )
        .build()
}
