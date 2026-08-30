package app.mediabattery

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mediabattery.oem.OemKeepAlive
import app.mediabattery.permissions.Permissions
import app.mediabattery.protect.DegradationCopy
import app.mediabattery.service.BatteryService
import app.mediabattery.ui.gauge.GaugeBanner
import app.mediabattery.ui.gauge.GaugeScreen
import app.mediabattery.ui.onboarding.OnboardingScreen
import app.mediabattery.ui.onboarding.OnboardingState
import app.mediabattery.ui.options.OptionsScreen
import app.mediabattery.ui.options.PermissionActions
import app.mediabattery.ui.sync.SyncScreen
import app.mediabattery.ui.sync.syncColor
import app.mediabattery.ui.theme.MediaBatteryTheme
import site.dirt23.battery.core.sync.SyncState

private enum class Route { Onboarding, Gauge, Settings, Sync }

/**
 * The app's only activity. Routing is one piece of state rather than a navigation library:
 * every destination sits one level below the gauge, and back always means the gauge.
 *
 * Permissions are re-read on every resume, and the watchdog re-runs with them. Granting any
 * of them takes a trip out to system settings, so coming back is when the answer changes.
 */
class MainActivity : ComponentActivity() {
    private lateinit var graph: AppGraph
    private var permissions by mutableStateOf(OnboardingState(false, false, false))

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph = (application as MediaBatteryApp).graph
        enableEdgeToEdge()
        refresh()

        setContent {
            MediaBatteryTheme {
                var route by remember { mutableStateOf(if (graph.prefs.onboarded) Route.Gauge else Route.Onboarding) }
                BackHandler(enabled = route != Route.Gauge && route != Route.Onboarding) { route = Route.Gauge }

                when (route) {
                    Route.Onboarding -> OnboardingScreen(
                        state = permissions,
                        onGrantUsage = { open(Permissions.usageAccessIntent()) },
                        onGrantOverlay = { open(Permissions.overlayIntent(this)) },
                        onGrantNotifications = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        onGrantAccessibility = { graph.capabilities.accessibilityIntent()?.let(::open) },
                        onGrantNotificationAccess = { open(Permissions.notificationAccessIntent()) },
                        onGrantBatteryExemption = { open(graph.capabilities.batteryExemptionIntent(this)) },
                        onOpenOem = ::openOem,
                        onDone = {
                            graph.prefs.onboarded = true
                            BatteryService.start(this)
                            route = Route.Gauge
                        },
                    )

                    Route.Gauge -> Gauge(
                        onOpenSettings = { route = Route.Settings },
                        onOpenSync = { route = Route.Sync },
                    )

                    Route.Settings -> OptionsScreen(
                        graph = graph,
                        permissions = permissions,
                        actions = permissionActions(),
                        onBack = { route = Route.Gauge },
                    )

                    Route.Sync -> SyncScreen(graph, onBack = { route = Route.Gauge })
                }
            }
        }
    }

    /** The rows on the settings page that lead back out to a system settings page. */
    private fun permissionActions() = PermissionActions(
        onUsage = { open(Permissions.usageAccessIntent()) },
        onOverlay = { open(Permissions.overlayIntent(this)) },
        onNotifications = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        onAccessibility = { graph.capabilities.accessibilityIntent()?.let(::open) },
        onNotificationAccess = { open(Permissions.notificationAccessIntent()) },
        onBatteryExemption = { open(graph.capabilities.batteryExemptionIntent(this)) },
        onOem = ::openOem,
    )

    /**
     * The vendor's own list of apps it will not stop, if this phone keeps one. Candidates
     * are tried until one opens: a vendor screen can resolve and still refuse to start.
     * The app details page underneath always works.
     */
    private fun openOem() {
        val vendor = OemKeepAlive.current() ?: return
        for (intent in OemKeepAlive.intents(this, vendor)) {
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
    }

    @Composable
    private fun Gauge(
        onOpenSettings: () -> Unit,
        onOpenSync: () -> Unit,
    ) {
        val snapshot by graph.engineHost.snapshot.collectAsStateWithLifecycle()
        val degradations by graph.watchdog.degradations.collectAsStateWithLifecycle()
        val syncStatus by graph.syncStatus.collectAsStateWithLifecycle()
        val context = LocalContext.current
        // Naming the app asks the package manager, and the snapshot arrives once a second
        // while draining, so key it on the id.
        val drainingLabel = remember(snapshot.drainingId) { graph.labels.draining(snapshot) }
        val banners = degradations.map { degradation ->
            GaugeBanner(context.getString(DegradationCopy.text(degradation))) {
                DegradationCopy.fix(this, graph.capabilities, degradation)?.let(::open)
            }
        }
        GaugeScreen(
            snap = snapshot,
            onOpenSettings = onOpenSettings,
            onOpenSync = onOpenSync,
            syncColor = syncColor(if (syncStatus.on) syncStatus.state else SyncState.OFF),
            drainingLabel = drainingLabel,
            banners = banners,
        )
    }

    override fun onResume() {
        super.onResume()
        refresh()
        graph.refreshNeverTrack()
        // Restarts the service if anything took it down, and resyncs a running one. Not
        // onCreate only: an activity brought back to front skips onCreate, which once left
        // a dead service dead until a force stop.
        if (graph.prefs.onboarded) BatteryService.start(this)
        // Gauge on screen, so a follower catches up. The extension does this on popup open.
        graph.pullSyncSoon()
    }

    private fun refresh() {
        permissions = OnboardingState(
            usageAccess = Permissions.hasUsageAccess(this),
            overlay = Permissions.canDrawOverlays(this),
            notifications = Permissions.hasNotifications(this),
            accessibility = graph.capabilities.accessibilityEnabled(this),
            notificationAccess = Permissions.hasNotificationAccess(this),
            batteryExempt = Permissions.ignoresBatteryOptimizations(this),
        )
        graph.watchdog.refresh()
    }

    private fun open(intent: Intent) {
        runCatching { startActivity(intent) }
    }
}
