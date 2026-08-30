package app.mediabattery.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mediabattery.ui.theme.SbColors

// The pieces every settings screen is built out of: the card, the segmented row, the
// stepper. They started inside the apps picker.
//
// Nothing here is a Material control with the colors changed. The app's surfaces are flat
// dark cards with a one pixel line and a green accent, and a component that brings its own
// elevation, ripple, and corner radius reads as a different app.

private val ButtonInk = Color(0xFF0F1115)

/** A screen title with the way back out. */
@Composable
fun ScreenHeader(title: String, back: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = SbColors.Ink,
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = back,
            color = SbColors.Muted,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onBack)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/**
 * A section heading: small, muted, hairline above, so the page reads as one list with
 * markers rather than four pages stacked.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, divider: Boolean = true) {
    Column(modifier.fillMaxWidth().padding(top = if (divider) 14.dp else 2.dp)) {
        if (divider) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(SbColors.Line))
            Spacer(Modifier.height(14.dp))
        }
        Text(
            text = text.uppercase(),
            color = SbColors.Muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
        )
    }
}

/** The card everything sits in. */
@Composable
fun SbCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SbColors.Surface)
            .border(1.dp, SbColors.Line, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        content = content,
    )
}

/** A card's heading, and the one line under it that says what the setting buys. */
@Composable
fun CardTitle(title: String, why: String? = null) {
    Text(title, color = SbColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    if (why != null) {
        Spacer(Modifier.height(6.dp))
        Text(why, color = SbColors.Muted, fontSize = 13.sp, lineHeight = 19.sp)
    }
}

/** One choice in a [Segmented] row: its label and the color it takes when picked. */
data class SegmentOption(val label: String, val color: Color = SbColors.Accent)

/** The app's picker shape: every option visible, the current one filled. */
@Composable
fun Segmented(
    options: List<SegmentOption>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SbColors.Bg)
            .border(1.dp, SbColors.Line, RoundedCornerShape(10.dp))
            .padding(3.dp),
    ) {
        options.forEachIndexed { index, option ->
            Segment(option.label, index == selected, option.color) { onSelect(index) }
        }
    }
}

@Composable
private fun RowScope.Segment(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) color else SbColors.Bg)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) SbColors.Ink else SbColors.Muted,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

/** A tappable label that reads as on or off. The picker's pill, reused for scope lists. */
@Composable
fun Pill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    color: Color = SbColors.Accent,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        color = if (selected) SbColors.Ink else SbColors.Muted,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) color else SbColors.Bg)
            .border(1.dp, if (selected) color else SbColors.Line, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
    )
}

/**
 * A number with a minus and a plus. No text field: every number here has a floor and a
 * ceiling, and a field that can be blanked can save a battery that never recharges.
 */
@Composable
fun Stepper(
    value: String,
    onStep: (Int) -> Unit,
    canDecrease: Boolean,
    canIncrease: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        StepButton("−", canDecrease) { onStep(-1) }
        Text(
            text = value,
            color = SbColors.Ink,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        StepButton("+", canIncrease) { onStep(1) }
    }
}

@Composable
private fun StepButton(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .alpha(if (enabled) 1f else 0.3f)
            .clip(RoundedCornerShape(10.dp))
            .background(SbColors.Bg)
            .border(1.dp, SbColors.Line, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = SbColors.Ink, fontSize = 19.sp, fontWeight = FontWeight.Bold)
    }
}

/** The green one, at most one per screen. */
@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Text(
        text = label,
        color = ButtonInk,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = modifier
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(10.dp))
            .background(SbColors.Accent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
    )
}

/** The bordered one, for anything that is not the main action on the screen. */
@Composable
fun SecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = SbColors.Ink,
) {
    Text(
        text = label,
        color = color,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = modifier
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(10.dp))
            .border(2.dp, SbColors.Line, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** A row of two things with a gap. */
@Composable
fun ButtonRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** A dot in a state color with a word next to it. */
@Composable
fun StatusChip(label: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (on) SbColors.Green else SbColors.Muted),
        )
        Spacer(Modifier.width(10.dp))
        Text(label, color = SbColors.Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
    }
}
