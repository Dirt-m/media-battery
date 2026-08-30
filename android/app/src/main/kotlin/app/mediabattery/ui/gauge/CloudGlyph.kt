package app.mediabattery.ui.gauge

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** The popup's cloud, same path as popup.html. Its color is the state; see the gauge. */
val CloudGlyph: ImageVector by lazy {
    ImageVector.Builder(
        name = "Cloud", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).path(
        stroke = SolidColor(Color.White),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(18f, 10f)
        horizontalLineToRelative(-1.26f)
        arcTo(8f, 8f, 0f, true, false, 9f, 20f)
        horizontalLineToRelative(9f)
        arcToRelative(5f, 5f, 0f, false, false, 0f, -10f)
        close()
    }.build()
}
