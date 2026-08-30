package app.mediabattery.block

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

// The cover's two glyphs, the same drawings the extension's block cover uses: a battery cell
// for a battery that ran out, a padlock for an app covered outright. Both are drawn on a 24
// unit grid and scaled to whatever size they are given.

private const val GRID = 24f

private fun DrawScope.stroke(width: Float) = Stroke(
    width = width,
    cap = StrokeCap.Round,
    join = StrokeJoin.Round,
    pathEffect = PathEffect.cornerPathEffect(0f),
)

@Composable
fun BatteryGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val u = size.minDimension / GRID
        val s = stroke(2f * u)
        drawRoundRect(
            color = color,
            topLeft = Offset(2f * u, 7f * u),
            size = Size(16f * u, 10f * u),
            cornerRadius = CornerRadius(2f * u, 2f * u),
            style = s,
        )
        drawLine(color, Offset(22f * u, 11f * u), Offset(22f * u, 13f * u), s.width, StrokeCap.Round)
        drawLine(color, Offset(6f * u, 12f * u), Offset(9f * u, 12f * u), s.width, StrokeCap.Round)
    }
}

@Composable
fun PadlockGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val u = size.minDimension / GRID
        val s = stroke(2f * u)
        drawRoundRect(
            color = color,
            topLeft = Offset(4f * u, 11f * u),
            size = Size(16f * u, 10f * u),
            cornerRadius = CornerRadius(2f * u, 2f * u),
            style = s,
        )
        val shackle = Path().apply {
            moveTo(8f * u, 11f * u)
            lineTo(8f * u, 7f * u)
            arcTo(
                rect = Rect(8f * u, 3f * u, 16f * u, 11f * u),
                startAngleDegrees = 180f,
                sweepAngleDegrees = 180f,
                forceMoveTo = false,
            )
            lineTo(16f * u, 11f * u)
        }
        drawPath(shackle, color, style = s)
    }
}
