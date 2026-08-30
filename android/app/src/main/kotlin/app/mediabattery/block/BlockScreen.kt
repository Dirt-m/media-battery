package app.mediabattery.block

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mediabattery.R
import app.mediabattery.ui.friction.FrictionGate
import app.mediabattery.ui.theme.SbColors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The cover's two stories, the same two the extension has. Dead: the battery ran out and
 * everything tracked is behind this until the cooldown lifts, with the status counting it
 * down while the charge climbs back. Blocked: this one app is covered by a setting or an
 * hour rule, and the battery is not involved.
 *
 * One way through, kept small on purpose: a quiet line of muted text rather than a button
 * asking to be pressed. Nothing here explains the rules again; the user set them.
 */
@Composable
fun BlockScreen(
    decision: BlockDecision,
    minutes: Int,
    gateOpen: Boolean,
    nameOf: (String) -> String = { it },
    onOpenGate: () -> Unit,
    onGateCancel: () -> Unit,
    onGateSuccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SbColors.Bg)
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (gateOpen) {
            FrictionGate(
                title = stringResource(R.string.block_gate_title, minutes),
                confirmLabel = stringResource(R.string.block_action, minutes),
                count = 1,
                askWhy = true,
                onCancel = onGateCancel,
                onSuccess = onGateSuccess,
                large = true,
                modifier = Modifier.widthIn(max = 460.dp),
            )
        } else {
            Card(decision, minutes, nameOf, onOpenGate)
        }
    }
}

@Composable
private fun Card(decision: BlockDecision, minutes: Int, nameOf: (String) -> String, onOpenGate: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.widthIn(max = 400.dp),
    ) {
        when (decision) {
            is BlockDecision.Dead -> BatteryGlyph(SbColors.Red, Modifier.size(54.dp))
            is BlockDecision.AppBlocked -> PadlockGlyph(SbColors.Red, Modifier.size(54.dp))
            BlockDecision.None -> return
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = title(decision, nameOf),
            color = SbColors.Ink,
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.3).sp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = status(decision),
            color = SbColors.Muted,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.height(40.dp))
        // Deliberately quiet: the way back in exists, but the page does not sell it.
        Text(
            text = stringResource(R.string.block_action, minutes),
            color = SbColors.Muted,
            fontSize = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onOpenGate)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun title(decision: BlockDecision, nameOf: (String) -> String): String = when (decision) {
    is BlockDecision.Dead -> stringResource(R.string.block_dead_title)
    is BlockDecision.AppBlocked -> stringResource(R.string.block_app_title, nameOf(decision.id))
    BlockDecision.None -> ""
}

@Composable
private fun status(decision: BlockDecision): String = when (decision) {
    is BlockDecision.Dead -> when {
        decision.cooldownRemaining > 0 ->
            stringResource(R.string.block_recharging_back_in, clock(decision.cooldownRemaining))
        decision.rechargePaused -> stringResource(R.string.block_charging_paused)
        else -> stringResource(R.string.block_recharging)
    }

    is BlockDecision.AppBlocked -> when {
        decision.untilMinuteOfDay != null ->
            stringResource(R.string.block_until, hhmm(decision.untilMinuteOfDay))
        decision.fromSettings -> stringResource(R.string.block_from_settings)
        else -> stringResource(R.string.block_by_hours)
    }

    BlockDecision.None -> ""
}

private fun clock(seconds: Double): String {
    val s = max(0.0, seconds).roundToInt()
    return "%d:%02d".format(s / 60, s % 60)
}

private fun hhmm(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
