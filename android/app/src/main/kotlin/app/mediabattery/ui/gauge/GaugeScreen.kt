package app.mediabattery.ui.gauge

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import app.mediabattery.R
import app.mediabattery.ui.theme.SbColors
import site.dirt23.battery.core.model.Snapshot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// The battery gauge, a port of the extension's popup: big M:SS, a state label, and a
// battery cell with a nub. Green above 20%; below that red while draining or dead,
// amber while climbing back. Bolt while actually climbing, arrow while draining.

private fun fmtClock(seconds: Double): String {
    val s = max(0.0, seconds).roundToInt()
    return "%d:%02d".format(s / 60, s % 60)
}

private enum class GaugeState { Ok, Low, Danger, Dead }

private fun state(snap: Snapshot, pct: Double): GaugeState = when {
    snap.depleted -> GaugeState.Dead
    pct <= 20 -> if (snap.draining) GaugeState.Danger else GaugeState.Low
    else -> GaugeState.Ok
}

/** One thing that is switched off, and the way to switch it back on. */
data class GaugeBanner(val text: String, val onFix: (() -> Unit)? = null)

@Composable
fun GaugeScreen(
    snap: Snapshot,
    onOpenSettings: () -> Unit,
    onOpenSync: () -> Unit,
    syncColor: Color,
    /** The app being drained, named. Null while the drain is another device's. */
    drainingLabel: String? = null,
    banners: List<GaugeBanner> = emptyList(),
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(SbColors.Bg)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Gauge(snap, drainingLabel)
        Icon(
            imageVector = CloudGlyph,
            contentDescription = null,
            tint = syncColor,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(22.dp))
                .clickable(onClickLabel = stringResource(R.string.gauge_sync), onClick = onOpenSync)
                .padding(11.dp)
                .size(22.dp),
        )
        GearGlyph(
            color = SbColors.Muted,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .clip(RoundedCornerShape(22.dp))
                .clickable(onClickLabel = stringResource(R.string.gauge_settings), onClick = onOpenSettings)
                .padding(11.dp)
                .size(22.dp),
        )
        if (banners.isNotEmpty()) {
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (banner in banners) DegradedBanner(banner)
            }
        }
    }
}

/** One line, one fix. */
@Composable
private fun DegradedBanner(banner: GaugeBanner, modifier: Modifier = Modifier) {
    val text = banner.text
    val onFix = banner.onFix
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SbColors.Surface)
            .border(1.dp, SbColors.Line, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = SbColors.Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (onFix != null) {
            Text(
                text = stringResource(R.string.gauge_fix),
                color = SbColors.Green,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .clickable(onClick = onFix)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun Gauge(snap: Snapshot, drainingLabel: String?) {
    val cap = if (snap.effCapacity > 0) snap.effCapacity else snap.capacity
    val pct = if (cap > 0) min(100.0, snap.charge / cap * 100.0) else 0.0
    val full = snap.charge >= cap
    val st = state(snap, pct)

    val chargeColor = when (st) {
        GaugeState.Ok -> SbColors.Green
        GaugeState.Low -> SbColors.Amber
        GaugeState.Danger, GaugeState.Dead -> SbColors.Red
    }

    val label = when {
        snap.depleted && snap.cooldownRemaining > 0 ->
            stringResource(R.string.gauge_blocked_for, fmtClock(snap.cooldownRemaining))
        snap.depleted -> stringResource(R.string.gauge_blocked)
        // No local app to name means the drain is mirrored from another device.
        snap.draining -> stringResource(
            R.string.gauge_draining,
            drainingLabel ?: stringResource(R.string.gauge_another_device),
        )
        full && cap < snap.capacity -> stringResource(R.string.gauge_capped_by_hours)
        full -> stringResource(R.string.gauge_full)
        snap.rechargePaused -> stringResource(R.string.gauge_charging_paused)
        else -> stringResource(R.string.gauge_recharging)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = fmtClock(snap.charge),
            color = chargeColor,
            fontSize = 72.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = label.uppercase(),
            color = SbColors.Muted,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
        )
        Spacer(Modifier.height(24.dp))
        BatteryCell(
            fillFraction = (pct / 100.0).toFloat(),
            fillColor = chargeColor,
            showBolt = !snap.draining && !full && !snap.rechargePaused,
            showDrain = snap.draining,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 360.dp),
        )
    }
}

@Composable
private fun BatteryCell(
    fillFraction: Float,
    fillColor: Color,
    showBolt: Boolean,
    showDrain: Boolean,
    modifier: Modifier = Modifier,
) {
    val animatedFill by animateFloatAsState(
        targetValue = fillFraction.coerceIn(0f, 1f),
        animationSpec = tween(300),
        label = "fill",
    )
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(72.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(SbColors.SurfaceRaised)
                .border(1.5.dp, SbColors.Line, RoundedCornerShape(14.dp))
                .padding(5.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedFill)
                    .clip(RoundedCornerShape(9.dp))
                    .background(fillColor),
            )
            if (showBolt) {
                Icon(
                    imageVector = FlowIcons.Bolt,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.Center).size(28.dp),
                )
            }
            if (showDrain) {
                Icon(
                    imageVector = FlowIcons.Drain,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.Center).size(26.dp),
                )
            }
        }
        Spacer(Modifier.width(3.dp))
        Box(
            modifier = Modifier
                .width(7.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(topEnd = 5.dp, bottomEnd = 5.dp))
                .background(SbColors.Line),
        )
    }
}
