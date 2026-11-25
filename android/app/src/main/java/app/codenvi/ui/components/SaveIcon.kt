package app.codenvi.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The Material "save" (floppy disk) icon. It lives in material-icons-extended, which is a very large dependency,
 * so the single path is defined here instead.
 */
val SaveIcon: ImageVector by lazy {
    ImageVector.Builder(name = "Save", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            PathParser().parsePathString(
                "M17,3L5,3c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2L21,7l-4,-4zM12,19c-1.66,0 -3,-1.34 -3,-3s1.34,-3 3,-3 3,1.34 3,3 -1.34,3 -3,3zM15,9L5,9L5,5h10v4z"
            ).toNodes(),
            fill = SolidColor(Color.Black),
        )
        .build()
}
