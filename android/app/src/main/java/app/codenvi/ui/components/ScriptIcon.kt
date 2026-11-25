package app.codenvi.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * A terminal window with a ">_" prompt, used for "run the project's script on the PC". It is drawn here as a single
 * even-odd path (window minus prompt) because material-icons-extended is a very large dependency.
 */
val ScriptIcon: ImageVector by lazy {
    ImageVector.Builder(name = "Terminal", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            PathParser().parsePathString(
                "M4,4h16a2,2 0 0 1 2,2v12a2,2 0 0 1 -2,2H4a2,2 0 0 1 -2,-2V6a2,2 0 0 1 2,-2z" +
                    "M6.5,9.2L7.7,8l4,4 -4,4 -1.2,-1.2L9.3,12z" +
                    "M12.5,14.4h5v1.6h-5z"
            ).toNodes(),
            pathFillType = PathFillType.EvenOdd,
            fill = SolidColor(Color.Black),
        )
        .build()
}
