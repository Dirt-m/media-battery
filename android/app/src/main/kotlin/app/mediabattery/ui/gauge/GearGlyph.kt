package app.mediabattery.ui.gauge

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The settings gear, drawn rather than pulling in a Compose icon pack for one glyph. */
@Composable
fun GearGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val u = size.minDimension / 24f
        val center = Offset(size.width / 2f, size.height / 2f)
        val stroke = Stroke(width = 2f * u, cap = StrokeCap.Round)
        drawCircle(color, radius = 6f * u, center = center, style = stroke)
        drawCircle(color, radius = 2.2f * u, center = center, style = stroke)
        repeat(8) { i ->
            val angle = i * PI / 4.0
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            drawLine(
                color = color,
                start = Offset(center.x + dx * 7.4f * u, center.y + dy * 7.4f * u),
                end = Offset(center.x + dx * 10f * u, center.y + dy * 10f * u),
                strokeWidth = 2f * u,
                cap = StrokeCap.Round,
            )
        }
    }
}
