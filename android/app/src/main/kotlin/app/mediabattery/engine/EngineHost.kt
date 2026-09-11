package app.mediabattery.engine

import app.mediabattery.block.BlockDecision
import app.mediabattery.block.BlockDecisions
import app.mediabattery.data.AppPrefs
import app.mediabattery.data.TrackedAppsStore
import app.mediabattery.detect.EngagementResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import site.dirt23.battery.core.SbClock
import site.dirt23.battery.core.engine.BatteryEngine
import site.dirt23.battery.core.engine.ReplayStep
import site.dirt23.battery.core.engine.SettingsPatch
import site.dirt23.battery.core.identity.AppCatalog
import site.dirt23.battery.core.model.HourRule
import site.dirt23.battery.core.model.SiteMode
import site.dirt23.battery.core.model.Snapshot
import site.dirt23.battery.core.rules.Rules

/** One transition, as the replayer reconstructs it: who was engaged, and from when. */
data class Transition(val id: String?, val atMs: Long)

/** What the app layer knows about the settings the engine holds. */
data class SettingsView(
    val siteModes: Map<String, SiteMode> = emptyMap(),
    val hourRules: List<HourRule> = emptyList(),
)

private sealed interface EngineEvent {
    data class Foreground(val pkg: String?, val atMs: Long) : EngineEvent
    data class Playing(val packages: Set<String>, val atMs: Long) : EngineEvent
    data class Screen(val interactive: Boolean, val locked: Boolean, val atMs: Long) : EngineEvent
    data class IdleDue(val atMs: Long, val token: Long) : EngineEvent
    data class SetMode(val id: String, val mode: SiteMode) : EngineEvent
    data class ApplyLocal(val patch: SettingsPatch, val keys: List<String>) : EngineEvent
    data class ApplyRemote(val patch: SettingsPatch) : EngineEvent
    data class Replay(val transitions: List<Transition>, val ack: CompletableDeferred<Unit>) : EngineEvent
    data class UseReserve(val ack: CompletableDeferred<Unit>) : EngineEvent
    data class UsePass(val id: String, val ack: CompletableDeferred<Unit>) : EngineEvent
    data class Settle(val ack: CompletableDeferred<Unit>) : EngineEvent
    data object Tick : EngineEvent
    data object ClockChanged : EngineEvent
    data object TrackedChanged : EngineEvent
}

/**
 * The engine's only caller. Everything that can move the battery arrives as an event on one
 * channel, handled by one coroutine on a single threaded dispatcher. The engine locks
 * internally, so this is about order, not safety: a foreground change, a tick and a
 * friction pass landing together have to apply in the order they happened.
 *
 * Two things the host owns and the engine does not:
 *
 * The trailing debounce. Leaving a tracked app waits 250ms before it counts, and if the
 * same app comes back inside that window nothing is reported at all. A different app
 * cancels the wait and the leave is reported at the moment it happened, so the gap counts
 * for nobody.
 *
 * The tick cadence. 1s while the charge is moving or a countdown is on screen, 5s while the
 * screen is on and idle, 5s while the screen is off and something is playing, nothing while
 * the screen is off and silent. The last one is safe because the engine works on timestamp
 * deltas: six dark hours settle on the first tick after the screen comes back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EngineHost(
    private val engine: BatteryEngine,
    private val tracked: TrackedAppsStore,
    private val clock: SbClock,
    private val prefs: AppPrefs,
    initialSettings: SettingsView,
    private val scope: CoroutineScope,
) {
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val events = Channel<EngineEvent>(Channel.UNLIMITED)

    private val _snapshot = MutableStateFlow(engine.snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private val _decision = MutableStateFlow<BlockDecision>(BlockDecision.None)
    val decision: StateFlow<BlockDecision> = _decision.asStateFlow()

    /**
     * The app's copy of the settings the engine holds. The engine takes whole maps in and
     * publishes none back, so the layer that writes them keeps the mirror.
     */
    private val _settings = MutableStateFlow(initialSettings)
    val settings: StateFlow<SettingsView> = _settings.asStateFlow()

    /**
     * The three things sync wants to hear about. Hooks rather than a dependency, because the
     * sync engine is built from this host and cannot also be handed to it; the graph sets
     * them once. All three fire on the consumer's dispatcher, so a hook that touches the
     * sync engine has to hop to the scope that engine is confined to.
     */
    var onEngagementChanged: ((Boolean) -> Unit)? = null

    /** A settings key changed locally, named as it travels. */
    var onSettingsChanged: ((String) -> Unit)? = null

    /** A deliberate one off charge change: reserve, or a pass. */
    var onChargeAction: (() -> Unit)? = null

    // Consumer state, written only from the consumer coroutine. engagedId is also read by
    // [hot] on the detector's thread, hence the volatile.
    private var foregroundPackage: String? = null
    private var playingPackages: Set<String> = emptySet()
    private var interactive = true
    private var locked = false

    @Volatile
    private var engagedId: String? = null
    private var pendingIdleAt: Long? = null
    private var idleToken = 0L
    private var lastPersistedSeen = 0L

    private val tickInterval = MutableStateFlow<Long?>(IDLE_TICK_MS)
    private var consumer: Job? = null
    private var ticker: Job? = null

    // [start] and [stop] both run on the main thread. These bridge the one place they can
    // interleave: the suspension inside stop's settle.
    private var stopping = false
    private var restartWanted = false

    /** True while a late foreground answer would cost something. Paces the detector. */
    fun hot(): Boolean = engagedId != null || _decision.value != BlockDecision.None

    fun start() {
        if (consumer != null) return
        if (stopping) {
            // A restart raced the teardown's settle. Deferred to the end of stop() so
            // there is only ever one consumer. Without it the resumed stop() cancelled the
            // consumer this call started, leaving a running service with no reader.
            restartWanted = true
            return
        }
        consumer = scope.launch(dispatcher) {
            for (event in events) handle(event)
        }
        ticker = scope.launch(dispatcher) {
            tickInterval.collectLatest { ms ->
                if (ms == null) return@collectLatest
                while (isActive) {
                    delay(ms)
                    events.trySend(EngineEvent.Tick)
                }
            }
        }
        events.trySend(EngineEvent.Tick)
    }

    /**
     * Settle to now and write the state down. The engine saves on transitions and deliberate
     * actions but not on a plain tick, and an idle report is both a settle and a save.
     */
    suspend fun stop() {
        if (consumer == null || stopping) return
        stopping = true
        val ack = CompletableDeferred<Unit>()
        events.trySend(EngineEvent.Settle(ack))
        ack.await()
        ticker?.cancel()
        consumer?.cancel()
        ticker = null
        consumer = null
        stopping = false
        if (restartWanted) {
            restartWanted = false
            start()
        }
    }

    // --- Inbound ------------------------------------------------------------------

    fun onForeground(pkg: String?, atMs: Long) {
        events.trySend(EngineEvent.Foreground(pkg, atMs))
    }

    /** The packages heard playing right now, in the order they started. */
    fun onPlaying(packages: Set<String>, atMs: Long) {
        events.trySend(EngineEvent.Playing(packages, atMs))
    }

    fun onScreen(interactive: Boolean, locked: Boolean, atMs: Long) {
        events.trySend(EngineEvent.Screen(interactive, locked, atMs))
    }

    fun onClockChanged() {
        events.trySend(EngineEvent.ClockChanged)
    }

    fun onTrackedChanged() {
        events.trySend(EngineEvent.TrackedChanged)
    }

    fun setMode(id: String, mode: SiteMode) {
        events.trySend(EngineEvent.SetMode(id, mode))
    }

    /**
     * A settings save made on this device. [keys] names what changed in the wire's own key
     * names, so each gets its own timestamp and merges independently on the other devices.
     * A key left out is a key this save did not touch.
     */
    fun applySettings(patch: SettingsPatch, keys: List<String>) {
        events.trySend(EngineEvent.ApplyLocal(patch, keys))
    }

    /** Settings merged in from another device. The engine sanitizes; this only orders. */
    fun applyRemoteSettings(patch: SettingsPatch) {
        events.trySend(EngineEvent.ApplyRemote(patch))
    }

    /** Settle and republish now, for when something outside the host moved the charge. */
    fun refresh() {
        events.trySend(EngineEvent.Tick)
    }

    suspend fun useReserve() {
        val ack = CompletableDeferred<Unit>()
        events.trySend(EngineEvent.UseReserve(ack))
        ack.await()
    }

    suspend fun useSitePass(id: String) {
        val ack = CompletableDeferred<Unit>()
        events.trySend(EngineEvent.UsePass(id, ack))
        ack.await()
    }

    /**
     * Historical transitions, in order. Enqueue before [start]: the channel is FIFO, and a
     * live event applied first would settle the engine to now and clamp every replayed
     * transition to zero elapsed time. The returned deferred resolves once the consumer has
     * applied them, and anything that reaches the engine outside the channel (the sync
     * engine) has to wait on it.
     */
    fun replay(transitions: List<Transition>): CompletableDeferred<Unit> {
        val ack = CompletableDeferred<Unit>()
        if (transitions.isEmpty()) {
            ack.complete(Unit)
        } else {
            events.trySend(EngineEvent.Replay(transitions, ack))
        }
        return ack
    }

    // --- The consumer -------------------------------------------------------------

    private suspend fun handle(event: EngineEvent) {
        when (event) {
            is EngineEvent.Foreground -> {
                foregroundPackage = event.pkg
                settle(event.atMs)
            }

            is EngineEvent.Playing -> {
                playingPackages = event.packages
                settle(event.atMs)
            }

            is EngineEvent.Screen -> {
                interactive = event.interactive
                locked = event.locked
                // The ticker is off while the screen is, so a wake settles the dark gap.
                publish(engine.tick())
                settle(event.atMs)
            }

            is EngineEvent.IdleDue -> {
                // A stale token means the wait was cancelled: the app came back, or
                // engagement already moved on.
                if (event.token == idleToken && pendingIdleAt != null) {
                    pendingIdleAt = null
                    engagedId = null
                    publish(engine.onIdle(event.atMs))
                }
            }

            is EngineEvent.SetMode -> {
                val modes = LinkedHashMap(_settings.value.siteModes)
                modes[event.id] = event.mode
                _settings.value = _settings.value.copy(siteModes = modes)
                publish(engine.applySettings(SettingsPatch(siteModes = modes)))
                settle(clock.wallNow())
                // Every mode travels, app ids included: the extension carries ids it has no
                // row for verbatim, and another phone on the profile adopts them.
                onSettingsChanged?.invoke("enabledSites")
            }

            is EngineEvent.ApplyLocal -> {
                mirror(event.patch)
                publish(engine.applySettings(event.patch))
                settle(clock.wallNow())
                // One stamp per key, so a rule edited here and a capacity changed on the
                // desktop both survive the merge.
                for (key in event.keys) onSettingsChanged?.invoke(key)
            }

            is EngineEvent.ApplyRemote -> {
                mirror(event.patch)
                publish(engine.applySettings(event.patch))
                settle(clock.wallNow())
            }

            is EngineEvent.Replay -> {
                // Applied silently: publishing each step would run the cover, the
                // notification and the sync engagement hook through past states, and a
                // replay must never push a historical anchor to the server. One engine
                // call, not a step at a time: the live transitions settle to now after
                // each flip, which would spend the first engaged step's time to the present.
                engine.replay(event.transitions.map { ReplayStep(it.id, it.atMs) })
                // The live signals, not the log, decide what holds now.
                engagedId = null
                settle(clock.wallNow())
                event.ack.complete(Unit)
            }

            is EngineEvent.UseReserve -> {
                publish(engine.useReserve())
                settle(clock.wallNow())
                event.ack.complete(Unit)
                onChargeAction?.invoke()
            }

            is EngineEvent.UsePass -> {
                publish(engine.useSitePass(event.id))
                settle(clock.wallNow())
                event.ack.complete(Unit)
                onChargeAction?.invoke()
            }

            is EngineEvent.Settle -> {
                pendingIdleAt = null
                engagedId = null
                publish(engine.onIdle(clock.wallNow()))
                event.ack.complete(Unit)
            }

            EngineEvent.Tick -> {
                publish(engine.tick())
                // A pass expiring or an hour window opening changes whether this app still
                // counts.
                settle(clock.wallNow())
                persistSeen()
            }

            EngineEvent.ClockChanged -> {
                publish(engine.onWallClockChanged())
                settle(clock.wallNow())
            }

            EngineEvent.TrackedChanged -> settle(clock.wallNow())
        }
    }

    /**
     * Update the app's copy from the same patch, through the same validator. Fields the
     * patch leaves out are fields the save did not touch.
     */
    private fun mirror(patch: SettingsPatch) {
        _settings.value = SettingsView(
            siteModes = patch.siteModes ?: _settings.value.siteModes,
            hourRules = patch.hourRules?.let { Rules.sanitizeRules(it) } ?: _settings.value.hourRules,
        )
    }

    /**
     * Re-derive who is engaged and tell the engine, applying the trailing debounce on the
     * way out. [atMs] is the moment the signal that triggered this happened.
     */
    private fun settle(atMs: Long) {
        val want = EngagementResolver.engagedId(
            foregroundPackage,
            interactive,
            locked,
            playingPackages,
            tracked::isTracked,
        ) { engine.siteUsable(it) }

        if (want == engagedId) {
            // Back inside the debounce window: one continuous stretch, nothing reported.
            pendingIdleAt = null
            idleToken++
            republish()
            return
        }

        if (want == null) {
            if (pendingIdleAt == null) {
                pendingIdleAt = atMs
                val token = ++idleToken
                scope.launch(dispatcher) {
                    delay(LEAVE_DEBOUNCE_MS)
                    events.trySend(EngineEvent.IdleDue(atMs, token))
                }
            }
            republish()
            return
        }

        // A different app. Close the pending leave at the moment it happened, so the gap
        // between the two counts for neither, then open the new one.
        pendingIdleAt?.let { publish(engine.onIdle(it)) }
        pendingIdleAt = null
        idleToken++
        engagedId = want
        publish(engine.onEngaged(want, atMs))
    }

    private fun publish(snapshot: Snapshot) {
        // Engagement as sync means it: this device is draining, so it owns the shared
        // charge until it stops.
        val was = _snapshot.value.drainingId != null
        _snapshot.value = snapshot
        _decision.value = decide(snapshot)
        tickInterval.value = cadence(snapshot)
        val now = snapshot.drainingId != null
        if (now != was) onEngagementChanged?.invoke(now)
    }

    private fun republish() = publish(_snapshot.value)

    private fun decide(snapshot: Snapshot): BlockDecision {
        val pkg = foregroundPackage
        if (!interactive || locked || pkg == null || !tracked.isTracked(pkg)) return BlockDecision.None
        val id = AppCatalog.idFor(pkg)
        val view = _settings.value
        return BlockDecisions.decide(
            snapshot = snapshot,
            id = id,
            mode = view.siteModes[id] ?: SiteMode.ON,
            rules = view.hourRules,
            nowWallMs = clock.wallNow(),
            zone = clock.zone(),
        )
    }

    private fun cadence(snapshot: Snapshot): Long? = when {
        // Only a tracked player earns a tick with the screen off. The monitor publishes
        // every playing app, and an untracked one must not keep a 5s timer up all night.
        !interactive -> if (playingPackages.none(tracked::isTracked)) null else IDLE_TICK_MS
        engagedId != null -> LIVE_TICK_MS
        snapshot.depleted -> LIVE_TICK_MS
        else -> IDLE_TICK_MS
    }

    /**
     * A floor for the next start's replay window. Written periodically rather than every
     * tick: an old floor only costs a longer walk through the event log. Nothing is written
     * while the screen is off and idle, so the heartbeat does not touch the disk all night.
     */
    private fun persistSeen() {
        if (!interactive && engagedId == null) return
        val now = clock.wallNow()
        if (now - lastPersistedSeen < PERSIST_SEEN_EVERY_MS) return
        lastPersistedSeen = now
        prefs.lastSeenMs = now
    }

    private companion object {
        const val LEAVE_DEBOUNCE_MS = 250L
        const val LIVE_TICK_MS = 1_000L
        const val IDLE_TICK_MS = 5_000L
        const val PERSIST_SEEN_EVERY_MS = 15_000L
    }
}
