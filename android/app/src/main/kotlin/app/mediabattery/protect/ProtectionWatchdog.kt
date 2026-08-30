package app.mediabattery.protect

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import app.mediabattery.R
import app.mediabattery.detect.ScreenStateMonitor
import app.mediabattery.flavor.Capabilities
import app.mediabattery.permissions.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A grant that was switched off while the app was running. The system tells the user
 * nothing, so the app has to notice and say so in one line.
 *
 * Critical means it does not work at all: nothing is counted, or nothing can be covered. The
 * rest are worth knowing and read as facts, not demands.
 */
enum class Degradation(val critical: Boolean) {
    UsageAccessOff(true),
    OverlayOff(true),
    AccessibilityOff(false),
    ListenerOff(false),
    BatteryOptimized(false),
}

/**
 * Re-evaluated when the app comes back to the front, when the service starts, and every
 * fifteen minutes in between. A plain coroutine rather than scheduled work: the answer only
 * matters while there is something to show it to. The loop parks while the screen is off,
 * the same way the detector does, since revoking a grant takes a lit screen and the wake's
 * first pass catches it.
 */
class ProtectionWatchdog(
    private val context: Context,
    private val capabilities: Capabilities,
    private val screen: ScreenStateMonitor,
    private val scope: CoroutineScope,
) {
    private val state = MutableStateFlow<Set<Degradation>>(emptySet())

    /** In declaration order, so the critical ones read first wherever they are shown. */
    val degradations: StateFlow<Set<Degradation>> = state.asStateFlow()

    private var periodic: Job? = null

    fun refresh() {
        state.value = Degradation.entries.filterTo(LinkedHashSet()) { off(it) }
    }

    fun startPeriodic() {
        if (periodic != null) return
        periodic = scope.launch(Dispatchers.Default) {
            while (isActive) {
                if (!screen.interactive.value) screen.interactive.first { it }
                refresh()
                delay(PERIOD_MS)
            }
        }
    }

    fun stopPeriodic() {
        periodic?.cancel()
        periodic = null
    }

    private fun off(degradation: Degradation): Boolean = when (degradation) {
        Degradation.UsageAccessOff -> !Permissions.hasUsageAccess(context)
        // The grant is the background activity launch exemption. Without it the launch is
        // refused and nothing gets covered.
        Degradation.OverlayOff -> !Permissions.canDrawOverlays(context)
        // Null means a build with no such service, not a degradation.
        Degradation.AccessibilityOff -> capabilities.accessibilityEnabled(context) == false
        Degradation.ListenerOff -> !Permissions.hasNotificationAccess(context)
        Degradation.BatteryOptimized -> !Permissions.ignoresBatteryOptimizations(context)
    }

    private companion object {
        const val PERIOD_MS = 15 * 60 * 1000L
    }
}

/** The one line each degradation gets, and the settings screen that fixes it. */
object DegradationCopy {
    @StringRes
    fun text(degradation: Degradation): Int = when (degradation) {
        Degradation.UsageAccessOff -> R.string.degraded_usage
        Degradation.OverlayOff -> R.string.degraded_overlay
        Degradation.AccessibilityOff -> R.string.degraded_accessibility
        Degradation.ListenerOff -> R.string.degraded_listener
        Degradation.BatteryOptimized -> R.string.degraded_battery
    }

    fun fix(context: Context, capabilities: Capabilities, degradation: Degradation): Intent? =
        when (degradation) {
            Degradation.UsageAccessOff -> Permissions.usageAccessIntent()
            Degradation.OverlayOff -> Permissions.overlayIntent(context)
            Degradation.AccessibilityOff -> capabilities.accessibilityIntent()
            Degradation.ListenerOff -> Permissions.notificationAccessIntent()
            Degradation.BatteryOptimized -> capabilities.batteryExemptionIntent(context)
        }
}
