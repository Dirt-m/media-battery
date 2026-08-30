package app.mediabattery.ui.gauge

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// The popup's flow icons, same paths as popup.html: a bolt while charging, a down
// arrow while draining.
object FlowIcons {
    val Bolt: ImageVector by lazy {
        ImageVector.Builder(
            name = "Bolt", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).path(fill = SolidColor(Color.White)) {
            moveTo(13f, 2f)
            lineTo(4.9f, 13.4f)
            horizontalLineToRelative(4.6f)
            lineTo(8.6f, 22f)
            lineToRelative(8.5f, -11.4f)
            horizontalLineToRelative(-4.6f)
            lineTo(13f, 2f)
            close()
        }.build()
    }

    val Drain: ImageVector by lazy {
        ImageVector.Builder(
            name = "Drain", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).path(fill = SolidColor(Color.White)) {
            moveTo(12f, 21.5f)
            lineTo(5.5f, 14f)
            horizontalLineToRelative(3.7f)
            verticalLineTo(3f)
            horizontalLineToRelative(5.6f)
            verticalLineToRelative(11f)
            horizontalLineToRelative(3.7f)
            lineTo(12f, 21.5f)
            close()
        }.build()
    }
}
