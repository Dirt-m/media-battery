package app.mediabattery.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mediabattery.R
import app.mediabattery.oem.OemKeepAlive
import app.mediabattery.ui.theme.MediaBatteryTheme
import app.mediabattery.ui.theme.SbColors

// First run, one sentence per grant. Usage access is the only one that gates the finish
// button: without it there is no way to tell which app is in front. The three below the
// fold are optional.

private val ButtonInk = Color(0xFF0F1115)

data class OnboardingState(
    val usageAccess: Boolean,
    val overlay: Boolean,
    val notifications: Boolean,
    /** Null in a build that ships no accessibility service, so the step is not shown. */
    val accessibility: Boolean? = null,
    val notificationAccess: Boolean = false,
    val batteryExempt: Boolean = false,
)

@Composable
fun OnboardingScreen(
    state: OnboardingState,
    onGrantUsage: () -> Unit,
    onGrantOverlay: () -> Unit,
    onGrantNotifications: () -> Unit,
    onGrantAccessibility: () -> Unit,
    onGrantNotificationAccess: () -> Unit,
    onGrantBatteryExemption: () -> Unit,
    onOpenOem: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Only on a phone whose vendor keeps a list of its own. Nothing can be read back, so it
    // never shows a state and never blocks the finish button.
    val vendor = remember { OemKeepAlive.current() }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SbColors.Bg)
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 32.dp),
    ) {
        Text(
            text = stringResource(R.string.onboarding_heading),
            color = SbColors.Ink,
            fontSize = 30.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_sub),
            color = SbColors.Muted,
            fontSize = 15.sp,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.height(28.dp))

        PermissionCard(
            title = stringResource(R.string.onboarding_usage_title),
            why = stringResource(R.string.onboarding_usage_why),
            required = true,
            granted = state.usageAccess,
            onGrant = onGrantUsage,
        )
        Spacer(Modifier.height(14.dp))
        PermissionCard(
            title = stringResource(R.string.onboarding_overlay_title),
            why = stringResource(R.string.onboarding_overlay_why),
            required = true,
            granted = state.overlay,
            onGrant = onGrantOverlay,
            note = stringResource(R.string.onboarding_overlay_note),
        )
        Spacer(Modifier.height(14.dp))
        PermissionCard(
            title = stringResource(R.string.onboarding_notifications_title),
            why = stringResource(R.string.onboarding_notifications_why),
            required = false,
            granted = state.notifications,
            onGrant = onGrantNotifications,
        )
        state.accessibility?.let { granted ->
            Spacer(Modifier.height(14.dp))
            PermissionCard(
                title = stringResource(R.string.onboarding_accessibility_title),
                why = stringResource(R.string.onboarding_accessibility_why),
                required = false,
                granted = granted,
                onGrant = onGrantAccessibility,
            )
        }
        Spacer(Modifier.height(14.dp))
        PermissionCard(
            title = stringResource(R.string.onboarding_listener_title),
            why = stringResource(R.string.onboarding_listener_why),
            required = false,
            granted = state.notificationAccess,
            onGrant = onGrantNotificationAccess,
        )
        Spacer(Modifier.height(14.dp))
        PermissionCard(
            title = stringResource(R.string.onboarding_exempt_title),
            why = stringResource(R.string.onboarding_exempt_why),
            required = false,
            granted = state.batteryExempt,
            onGrant = onGrantBatteryExemption,
        )
        vendor?.let {
            Spacer(Modifier.height(14.dp))
            PermissionCard(
                title = stringResource(R.string.oem_title),
                why = stringResource(it.line),
                required = false,
                granted = false,
                onGrant = onOpenOem,
                actionLabel = stringResource(R.string.oem_open),
            )
        }

        Spacer(Modifier.height(30.dp))
        Text(
            text = stringResource(R.string.onboarding_done),
            color = ButtonInk,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (state.usageAccess) 1f else 0.45f)
                .clip(RoundedCornerShape(11.dp))
                .background(SbColors.Accent)
                .clickable(enabled = state.usageAccess, onClick = onDone)
                .padding(vertical = 15.dp),
        )
    }
}

/** Required grants get a filled card; the optional ones are the same shape unfilled. */
@Composable
private fun PermissionCard(
    title: String,
    why: String,
    required: Boolean,
    granted: Boolean,
    onGrant: () -> Unit,
    note: String? = null,
    actionLabel: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (required) SbColors.Surface else Color.Transparent)
            .border(1.dp, SbColors.Line, RoundedCornerShape(14.dp))
            .padding(horizontal = 20.dp, vertical = if (required) 22.dp else 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                color = SbColors.Ink,
                fontSize = if (required) 18.sp else 16.sp,
                fontWeight = if (required) FontWeight.Bold else FontWeight.SemiBold,
            )
            if (required) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.onboarding_required).uppercase(),
                    color = SbColors.Muted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = why,
            color = SbColors.Muted,
            fontSize = 14.sp,
            lineHeight = 21.sp,
        )
        Spacer(Modifier.height(14.dp))
        if (granted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CheckMark()
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.onboarding_on),
                    color = SbColors.Green,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        } else {
            if (note != null) {
                Text(
                    text = note,
                    color = SbColors.Muted,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                )
                Spacer(Modifier.height(12.dp))
            }
            Text(
                text = actionLabel ?: stringResource(R.string.onboarding_grant),
                color = SbColors.Ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .border(2.dp, SbColors.Line, RoundedCornerShape(10.dp))
                    .clickable(onClick = onGrant)
                    .padding(horizontal = 18.dp, vertical = 11.dp),
            )
        }
    }
}

@Composable
private fun CheckMark() {
    Canvas(modifier = Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        drawLine(
            color = SbColors.Green,
            start = Offset(w * 0.12f, h * 0.55f),
            end = Offset(w * 0.40f, h * 0.83f),
            strokeWidth = w * 0.16f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = SbColors.Green,
            start = Offset(w * 0.40f, h * 0.83f),
            end = Offset(w * 0.90f, h * 0.20f),
            strokeWidth = w * 0.16f,
            cap = StrokeCap.Round,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF14181E, widthDp = 380, heightDp = 800)
@Composable
private fun OnboardingPreview() {
    MediaBatteryTheme {
        OnboardingScreen(
            state = OnboardingState(
                usageAccess = true,
                overlay = false,
                notifications = false,
                accessibility = false,
            ),
            onGrantUsage = {},
            onGrantOverlay = {},
            onGrantNotifications = {},
            onGrantAccessibility = {},
            onGrantNotificationAccess = {},
            onGrantBatteryExemption = {},
            onOpenOem = {},
            onDone = {},
        )
    }
}
