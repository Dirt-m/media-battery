package app.mediabattery

import android.app.Application
import app.mediabattery.block.BlockController
import app.mediabattery.data.AppLabels
import app.mediabattery.data.AppPrefs
import app.mediabattery.data.NeverTrack
import app.mediabattery.data.TrackedAppsStore
import app.mediabattery.detect.DetectorSupervisor
import app.mediabattery.detect.ScreenStateMonitor
import app.mediabattery.detect.UsageStatsForegroundDetector
import app.mediabattery.engine.AndroidSbClock
import app.mediabattery.engine.EngineHost
import app.mediabattery.engine.SettingsView
import app.mediabattery.flavor.Capabilities
import app.mediabattery.flavor.FlavorCapabilities
import app.mediabattery.media.MediaPlaybackMonitor
import app.mediabattery.protect.ProtectionWatchdog
import app.mediabattery.service.UsageEventsReplayer
import app.mediabattery.sync.EngineSyncAdapter
import app.mediabattery.sync.SyncStore
import app.mediabattery.warn.ArrivalPill
import app.mediabattery.warn.LowChargeWarner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import site.dirt23.battery.core.engine.BatteryEngine
import site.dirt23.battery.core.engine.FileStateStore
import site.dirt23.battery.core.model.SiteMode
import site.dirt23.battery.core.sync.SyncEngine
import site.dirt23.battery.core.sync.SyncStatus
import site.dirt23.battery.core.sync.UrlConnectionTransport

/**
 * The object graph, wired by hand in the Application. No DI framework.
 *
 * The battery outlives every screen and every service start, so it is built here and the
 * service borrows it: a settings screen opened with nothing running still reads a real
 * charge, and a service restart does not open a second engine over the same state file.
 * The service owns the running of it (ticker, detectors, receivers, cover).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppGraph(private val app: Application) {
    /**
     * Main.immediate: the cover window and the notification are main thread only, and
     * engine work runs on the host's own single threaded dispatcher anyway.
     */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val clock = AndroidSbClock

    private val store = FileStateStore(app.filesDir)
    // syncOn is read at stop time, after every property below has been built.
    val engine = BatteryEngine(store, clock, syncOn = { syncStore.record.value.syncCode != null })

    /**
     * On disk state, read once after the engine has booted from it. The engine takes whole
     * maps in and publishes none back, so the app's mirrors seed from the same file.
     */
    private val loaded = store.load()

    val prefs = AppPrefs(app)
    val neverTrack = NeverTrack(app)
    val tracked = TrackedAppsStore(app.filesDir, neverTrack)
    val labels = AppLabels(app)

    val engineHost = EngineHost(
        engine = engine,
        tracked = tracked,
        clock = clock,
        prefs = prefs,
        initialSettings = loadSettings(),
        scope = scope,
    )

    val syncStore = SyncStore(app.filesDir)

    val syncAdapter = EngineSyncAdapter(
        engine = engine,
        host = engineHost,
        store = syncStore,
        clock = clock,
        initialServerOffset = loaded?.serverOffset ?: 0L,
    )

    /**
     * SyncEngine expects a single caller, and its disk and crypto work has no business on
     * the thread that draws the gauge. Every call into it goes through [onSync].
     */
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    /**
     * Built with or without a code; inert until the sync screen calls `enable` on it. The
     * service starts and stops it.
     */
    val sync = SyncEngine(syncAdapter, UrlConnectionTransport(), syncScope)

    /** What sync is doing, for the gauge's cloud and the sync screen. */
    val syncStatus: StateFlow<SyncStatus> = syncAdapter.status

    /** Run something on the sync engine, on the one thread it is confined to. */
    fun onSync(block: suspend SyncEngine.() -> Unit): Job = syncScope.launch { sync.block() }

    val screen = ScreenStateMonitor(app)

    /** What this build can do beyond the shared floor. See `flavor/Capabilities`. */
    val capabilities: Capabilities = FlavorCapabilities

    private val fastDetector = capabilities.fastDetector(app)

    private val usageDetector = UsageStatsForegroundDetector(
        context = app,
        scope = scope,
        screen = screen,
        // The fast path already covers the enter edge, so the poll drops to its cold band
        // and only has to catch a leave nobody pushed.
        hot = { engineHost.hot() && fastDetector?.connected?.value != true },
    )

    val detectors = DetectorSupervisor(
        usage = usageDetector,
        fast = fastDetector,
        tracked = tracked,
        screen = screen,
        scope = scope,
    )

    val media = MediaPlaybackMonitor(
        context = app,
        scope = scope,
        isTracked = tracked::isTracked,
        onChanged = engineHost::onPlaying,
    )

    val replayer = UsageEventsReplayer(app, tracked, clock)

    val blocks = BlockController(context = app, scope = scope)

    val watchdog = ProtectionWatchdog(app, capabilities, screen, scope)

    /** The low charge warning. Its armed flag outlives any one service run. */
    val warner = LowChargeWarner(app)

    /** The time left, shown for a moment when a tracked app opens. */
    val pill = ArrivalPill(app)

    init {
        // Host hooks fire on the host's dispatcher, so each hops to the sync thread.
        engineHost.onEngagementChanged = { engaged -> onSync { onEngagementChange(engaged) } }
        engineHost.onSettingsChanged = { key -> onSync { onSettingsChanged(key) } }
        // Reserve and passes are one off changes on a device that usually is not the
        // writer, so push them instead of waiting for a drain to start.
        engineHost.onChargeAction = { onSync { pushChargeSoon() } }
    }

    /** Gauge back on screen: a follower catches up. */
    fun pullSyncSoon() = onSync { pullSoon() }

    /** Track or untrack a package and let the running engagement re-evaluate at once. */
    fun setTracked(pkg: String, trackedNow: Boolean) {
        if (trackedNow) tracked.track(pkg) else tracked.untrack(pkg)
        engineHost.onTrackedChanged()
    }

    fun setMode(id: String, mode: SiteMode) = engineHost.setMode(id, mode)

    fun refreshNeverTrack() = scope.launch(Dispatchers.Default) { neverTrack.refresh() }

    private fun loadSettings(): SettingsView {
        val state = loaded ?: return SettingsView()
        return SettingsView(siteModes = state.siteModes.toMap(), hourRules = state.hourRules)
    }
}
