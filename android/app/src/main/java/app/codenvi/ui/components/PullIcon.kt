package app.codenvi.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** A downward arrow over a baseline: "fetch and pull the latest origin/main". Mirror of [PublishIcon]. */
val PullIcon: ImageVector by lazy {
    ImageVector.Builder(name = "Pull", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            PathParser().parsePathString("M12,15L7,10h3V4h4v6h3z").toNodes(),
            fill = SolidColor(Color.Black),
        )
        .addPath(
            PathParser().parsePathString("M5,18h14v2H5z").toNodes(),
            fill = SolidColor(Color.Black),
        )
        .build()
}
