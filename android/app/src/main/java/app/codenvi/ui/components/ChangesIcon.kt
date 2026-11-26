package app.codenvi.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** The Material "difference" (two sheets with plus and minus) icon, defined here to avoid the large extended icon set. */
val ChangesIcon: ImageVector by lazy {
    ImageVector.Builder(name = "Difference", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            PathParser().parsePathString(
                "M18,23H4c-1.1,0 -2,-0.9 -2,-2V7h2v14h14V23zM15,1H8c-1.1,0 -1.99,0.9 -1.99,2L6,17c0,1.1 0.89,2 1.99,2H20c1.1,0 2,-0.9 2,-2V8L15,1zM17,15h-7v-2h7V15zM17,11h-2v2h-2v-2h-2V9h2V7h2v2h2V11z"
            ).toNodes(),
            fill = SolidColor(Color.Black),
        )
        .build()
}
