package site.dirt23.battery.core.engine

import site.dirt23.battery.core.Constants
import site.dirt23.battery.core.SbClock
import site.dirt23.battery.core.model.Anchor
import site.dirt23.battery.core.model.BatteryState
import site.dirt23.battery.core.model.CustomEntry
import site.dirt23.battery.core.model.RawHourRule
import site.dirt23.battery.core.model.SiteMode
import site.dirt23.battery.core.model.Snapshot
import site.dirt23.battery.core.projection.Projection
import site.dirt23.battery.core.projection.ProjectionEnv
import site.dirt23.battery.core.rules.Rules
import java.security.SecureRandom
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** What a sync pull should do with the anchor it just read. */
data class AdoptResult(
    val adopted: Boolean,
    /** True when the caller should overwrite the server with this device's lower charge. */
    val overwrite: Boolean = false,
)

/** One step of a replayed gap: who was engaged (null for nothing) from [atMs], device wall time. */
data class ReplayStep(val id: String?, val atMs: Long)

/**
 * A settings change, local or merged in from another device. Only the fields that are set
 * are applied, so an untouched setting cannot clobber another device's newer edit. Rules
 * arrive raw and go through [Rules.sanitizeRules].
 */
data class SettingsPatch(
    val rechargePerMin: Double? = null,
    val capacity: Double? = null,
    val warnSeconds: Int? = null,
    val siteModes: Map<String, SiteMode>? = null,
    val hourRules: List<RawHourRule?>? = null,
    val hideYtSidebar: Boolean? = null,
    val showTimeLeft: Boolean? = null,
    val frictionCount: Int? = null,
    val customEntries: List<CustomEntry>? = null,
)

/**
 * The authoritative timekeeper, ported from the extension's background.js.
 *
 * The battery is one banked charge in seconds. It drains a second per second while an app
 * the user is in counts against it, and recharges the rest of the time, including through
 * the dead period's cooldown. Time is always a timestamp delta, never an interval cadence,
 * so a process frozen for an hour settles that hour correctly on its next call.
 *
 * Nothing browser shaped survives: no tabs, no beats, no on screen leases, no badge. The
 * engagement model is level triggered instead, with the app layer saying who is engaged
 * ([onEngaged] / [onIdle]) and the engine deciding whether that counts ([siteUsable]).
 * Those two take a timestamp, unlike the extension, so a transition delivered late is
 * attributed to when it happened. The extension always used "now", which can only
 * over-drain; taking the earlier of the two can only under-drain.
 *
 * Every public method holds one lock and none of them suspend. The callers (a foreground
 * service tick, an accessibility event, a sync pull) are all short.
 */
class BatteryEngine(
    private val store: StateStore,
    private val clock: SbClock,
    private val guardClockJumps: Boolean = true,
) {
    private val lock = Any()
    private var state: BatteryState
    private var engagedId: String? = null

    /**
     * The monotonic reading at the last settle. Null on a cold start by design: the
     * monotonic clock has a fresh origin after a restart or reboot, so the first settle
     * establishes a baseline rather than reading a real offline gap as a clock jump.
     */
    private var lastMono: Long? = null

    init {
        val loaded = store.load() ?: BatteryState()
        state = loaded.sanitize(clock.wallNow() + loaded.serverOffset)
        if (state.deviceId == null) {
            state.deviceId = mintDeviceId()
            store.save(state)
        }
    }

    // --- Time ---------------------------------------------------------------------

    /**
     * Server anchored now. All timekeeping runs on this so a synced anchor's server time
     * stamps project correctly whatever this device's clock says. With sync off the offset
     * is 0 and this is the wall clock.
     */
    private fun sbNow(): Long = clock.wallNow() + state.serverOffset

    /**
     * Hour rules are local wall time, so rule evaluation converts back out of server time.
     * Both ends of a gap shift together, so durations do not change.
     */
    private fun wallTime(ts: Long): Long = ts - state.serverOffset

    // --- Engagement ----------------------------------------------------------------

    /**
     * Report that the user is in [id] right now. [atMs] is device wall time, defaulting to
     * now; pass the moment of the transition when delivering it late.
     */
    fun onEngaged(id: String, atMs: Long = clock.wallNow()): Snapshot =
        synchronized(lock) { transition(id, atMs) }

    /** Report that nothing tracked is in use. See [onEngaged] for [atMs]. */
    fun onIdle(atMs: Long = clock.wallNow()): Snapshot =
        synchronized(lock) { transition(null, atMs) }

    private fun transition(id: String?, atMs: Long): Snapshot {
        guardClock()
        val now = sbNow()
        // The period that just ended belongs to the OLD engagement, so settle before
        // flipping. Clamped to what we can still account for: not before the last settle
        // (that time is spent) and not after now.
        val at = (atMs + state.serverOffset).coerceIn(min(state.lastTickTs, now), now)
        recompute(at)
        engagedId = id
        recompute(now)
        store.save(state)
        return snapshotAt(now)
    }

    /**
     * Apply a gap the process slept through, reconstructed as timestamped [steps] in order.
     *
     * Not a loop over [onEngaged] and [onIdle]: those settle to now after the flip, which
     * for a replay drains the first engaged step all the way to the present and clamps every
     * later step to zero elapsed time (a ten minute evening session replayed the next
     * morning came out as a dead battery). Here each step settles only up to its own
     * moment, so the gap is spent exactly as the steps describe. Nothing is engaged after
     * the last step: the live signals decide what holds now, and the stretch since the last
     * step recharges, which can only under drain.
     */
    fun replay(steps: List<ReplayStep>): Snapshot = synchronized(lock) {
        guardClock()
        val now = sbNow()
        for (step in steps) {
            val at = (step.atMs + state.serverOffset).coerceIn(min(state.lastTickTs, now), now)
            recompute(at)
            engagedId = step.id
        }
        engagedId = null
        recompute(now)
        store.save(state)
        snapshotAt(now)
    }

    /** Settle the charge to now and read it. Called once a second. */
    fun tick(): Snapshot = synchronized(lock) {
        guardClock()
        val now = sbNow()
        recompute(now)
        snapshotAt(now)
    }

    /** Read the state without advancing it. */
    fun snapshot(): Snapshot = synchronized(lock) { snapshotAt(sbNow()) }

    /**
     * Does time in this app count right now? Off (settings or an open off rule) never counts
     * and is checked first, so an off rule exempts a site from a block.
     * Blocked counts only under a friction gated pass, so time behind a cover spends nothing.
     */
    fun siteUsable(id: String): Boolean =
        synchronized(lock) { usable(id, sbNow()) }

    private fun usable(id: String, now: Long): Boolean {
        if (state.modeOf(id) == SiteMode.OFF) return false
        val eff = Rules.siteEffectsAt(state.hourRules, id, wallTime(now), clock.zone())
        if (eff.off) return false
        val blocked = state.modeOf(id) == SiteMode.BLOCK || eff.blocked
        return !blocked || (state.sitePasses[id] ?: Long.MIN_VALUE) > now
    }

    /** The engaged app, or null when nothing is engaged or its time does not count. */
    private fun engagedNow(now: Long): String? = engagedId?.takeIf { usable(it, now) }

    /** True while the battery is dead and still inside its fixed cooldown. */
    private fun isCooling(now: Long): Boolean {
        val began = state.depletedAt ?: return false
        return state.depleted && (now - began) < Constants.COOLDOWN_SECONDS * 1000L
    }

    /**
     * A follower mirrors another device's live drain between pulls. The lease runs from the
     * anchor's write time, the same horizon the projection uses, so a writer that vanishes
     * mid drain stops mattering that long after its last write however often the stale
     * anchor is re-read.
     */
    private fun remoteDrainActive(now: Long): Boolean =
        state.remoteDraining && (now - state.remoteDrainingTs) < Constants.DRAIN_HORIZON_MS

    private fun isDraining(now: Long): Boolean =
        !state.depleted && (engagedNow(now) != null || remoteDrainActive(now))

    // --- The tick ------------------------------------------------------------------

    /** Close out the period since the last settle. Everything that moves the charge is here. */
    private fun recompute(now: Long) {
        val elapsedMs = max(0L, now - state.lastTickTs)
        state.lastTickTs = now
        // Expired passes fall away and the cover goes back up on the next read.
        state.sitePasses.entries.removeAll { it.value <= now }
        // The active device owns its own state; it never follows a remote drainer.
        if (engagedNow(now) != null) state.remoteDraining = false

        if (isDraining(now)) {
            // A dead battery covers every app, so it never counts as engaged. That plus a
            // cooldown lifting on a timer is what gets the charge back out of zero.
            state.charge = max(0.0, state.charge - elapsedMs / 1000.0)
        } else if (state.hourRules.isEmpty()) {
            state.charge = min(
                state.capacity,
                state.charge + (elapsedMs / 1000.0) * (state.rechargePerMin / 60.0),
            )
        } else {
            // The piecewise walk takes every window edge across the gap, so a night the
            // device slept through recharges what the windows allowed. With no rules it is
            // the flat line above, which is why that shortcut is safe.
            state.charge = Rules.projectRecharge(
                state.hourRules,
                state.charge,
                wallTime(now - elapsedMs),
                wallTime(now),
                Rules.RechargeEnv(state.capacity, state.rechargePerMin),
                clock.zone(),
            )
        }
        // An open capacity window caps the banked charge whichever way the tick went.
        state.charge = min(state.charge, capacityAt(now))

        if (!state.depleted) {
            if (state.charge <= 0) {
                state.depleted = true
                state.depletedAt = now
                state.depletionSeq += 1
                state.charge = 0.0
                // Write at the crossing. A kill before the next save would mint this seq
                // twice, and anything using it as an epoch would carry a pass into the next
                // dead period.
                store.save(state)
            }
        } else if (!isCooling(now) && state.charge > 0) {
            // Cooldown over and its recharge left something to spend. The charge > 0 half
            // stops a stopped charger from flapping dead and alive.
            state.depleted = false
            state.depletedAt = null
        }
        // Every settle is a monotonic baseline, so the next one can tell a clock jump from
        // time actually passing.
        lastMono = clock.monotonicNow()
    }

    private fun capacityAt(now: Long): Double =
        Rules.capacityAt(state.hourRules, wallTime(now), state.capacity, clock.zone())

    private fun snapshotAt(now: Long): Snapshot {
        val wall = wallTime(now)
        val zone = clock.zone()
        val cooldownRemaining = if (isCooling(now)) {
            max(0.0, Constants.COOLDOWN_SECONDS - (now - state.depletedAt!!) / 1000.0)
        } else {
            0.0
        }
        val passes = LinkedHashMap<String, Int>()
        for ((id, expiry) in state.sitePasses) {
            val left = (expiry - now) / 1000.0
            if (left > 0) passes[id] = ceil(left).toInt()
        }
        val engaged = engagedNow(now)
        return Snapshot(
            charge = state.charge,
            capacity = state.capacity.toInt(),
            effCapacity = capacityAt(now).toInt(),
            rechargePerMin = state.rechargePerMin,
            warnSeconds = state.warnSeconds,
            showTimeLeft = state.showTimeLeft,
            frictionCount = state.frictionCount,
            reserveSeconds = Constants.RESERVE_SECONDS,
            passSeconds = Constants.SITE_PASS_SECONDS,
            rechargePaused = Rules.chargeFactorAt(state.hourRules, wall, zone) == 0.0,
            cooldownRemaining = cooldownRemaining,
            depleted = state.depleted,
            depletedAt = state.depletedAt,
            depletionSeq = state.depletionSeq,
            draining = isDraining(now),
            // Null on a mirrored remote drain: no local app to name.
            drainingId = engaged,
            drainingName = engaged?.let { id -> state.customEntries.firstOrNull { it.id == id }?.name },
            sitePasses = passes,
        )
    }

    // --- Deliberate one-off actions -------------------------------------------------

    /**
     * Add the fixed top-up and revive the battery. Depletion clears globally, not per app,
     * so one friction pass brings everything back. The clamp is against the base capacity;
     * the settle right after brings it under any open capacity window.
     */
    fun useReserve(): Snapshot = synchronized(lock) {
        guardClock()
        recompute(sbNow())
        state.charge = BatteryState.clamp(state.charge + Constants.RESERVE_SECONDS, 0.0, state.capacity)
        if (state.charge > 0) {
            state.depleted = false
            state.depletedAt = null
        }
        val now = sbNow()
        recompute(now)
        store.save(state)
        snapshotAt(now)
    }

    /**
     * Buy a friction gated pass through a blocked app. The battery is untouched: the pass
     * opens that one app, and time on it drains as normal.
     */
    fun useSitePass(id: String): Snapshot = synchronized(lock) {
        guardClock()
        recompute(sbNow())
        state.sitePasses[id] = sbNow() + Constants.SITE_PASS_SECONDS * 1000L
        store.save(state)
        val now = sbNow()
        recompute(now) // the passed app can start draining right away
        snapshotAt(now)
    }

    // --- Settings --------------------------------------------------------------------

    /**
     * A settings save, from this device or merged in from another. One path for both; the
     * extension keeps two only because its local path also stamps the per key timestamps the
     * merge runs on. That stamping lives in EngineHost here.
     */
    fun applySettings(patch: SettingsPatch): Snapshot = synchronized(lock) { apply(patch) }

    private fun apply(patch: SettingsPatch): Snapshot {
        // Guard first, like every entry point: the settle below reads the wall clock as
        // elapsed time, so a jump since the last settle has to be cancelled before it is
        // spent. Then settle under the OLD settings (recompute then mutate, as in the
        // extension). Without it a save persists a stale lastTickTs and the period since
        // the last tick is re-read under the new settings.
        guardClock()
        recompute(sbNow())
        // Floor of 1: at 0 a dead battery never climbs back out.
        patch.rechargePerMin?.let {
            state.rechargePerMin = BatteryState.clamp(it, Constants.RECHARGE_PER_MIN_MIN, Constants.RECHARGE_PER_MIN_MAX)
        }
        patch.capacity?.let {
            state.capacity = BatteryState.clamp(
                it,
                Constants.CAPACITY_MIN_SECONDS.toDouble(),
                Constants.CAPACITY_MAX_SECONDS.toDouble(),
            )
        }
        patch.warnSeconds?.let { state.warnSeconds = min(BatteryState.WARN_SECONDS_MAX, max(0, it)) }
        patch.siteModes?.let { state.siteModes = LinkedHashMap(it) }
        patch.hourRules?.let { state.hourRules = Rules.sanitizeRules(it) }
        patch.hideYtSidebar?.let { state.hideYtSidebar = it }
        patch.showTimeLeft?.let { state.showTimeLeft = it }
        patch.frictionCount?.let { state.frictionCount = BatteryState.clampFrictionCount(it) }
        patch.customEntries?.let { state.customEntries = ArrayList(it) }
        // Banked charge cannot exceed a capacity the user just lowered.
        state.charge = BatteryState.clamp(state.charge, 0.0, state.capacity)

        val now = sbNow()
        recompute(now)
        store.save(state)
        return snapshotAt(now)
    }

    // --- Sync ------------------------------------------------------------------------

    /**
     * Lower charge wins. Adopt the remote anchor only if it projects to a charge at or below
     * ours, with an epsilon absorbing sub-second projection noise so followers do not churn.
     * If the remote is higher, keep the local value and tell the caller to overwrite the
     * server, unless that higher anchor is a live draining writer: overwriting then would
     * CAS ping-pong with the device in use, so it waits for the writer to go stale or idle.
     * A sync can lower this device's charge, never raise it.
     *
     * Adoption takes the projected state, not the raw anchor, so a remote depletion keeps
     * the moment the charge crossed zero and every device agrees on when the cooldown ends.
     * A device never adopts its own echo; local state is newer than anything it pushed.
     */
    fun maybeAdoptAnchor(a: Anchor): AdoptResult = synchronized(lock) { adopt(a) }

    private fun adopt(a: Anchor): AdoptResult {
        if (a.writer != null && a.writer == state.deviceId) return AdoptResult(adopted = false)
        guardClock()
        recompute(sbNow()) // settle local to now before comparing
        val now = sbNow()
        val p = Projection.projectAnchor(
            a,
            now,
            ProjectionEnv(
                capacity = state.capacity,
                rechargePerMin = state.rechargePerMin,
                cooldownSeconds = Constants.COOLDOWN_SECONDS,
                hourRules = state.hourRules,
                serverOffset = state.serverOffset,
                zone = clock.zone(),
            ),
        )
        if (p.charge > state.charge + Constants.ADOPT_EPS_SECONDS) {
            val writerIsLive = a.draining && now - a.asOf < Constants.DRAIN_HORIZON_MS
            // A newer stopped anchor while mirroring means the writer stopped. Its value
            // is not taken (lower wins), but the mirror ends now instead of at the lease,
            // so the phantom drain stops at the detection latency.
            if (!a.draining && state.remoteDraining && a.asOf >= state.remoteDrainingTs) {
                state.remoteDraining = false
                store.save(state)
            }
            return AdoptResult(adopted = false, overwrite = !writerIsLive)
        }

        state.charge = BatteryState.clamp(p.charge, 0.0, state.capacity)
        // A lower but living anchor lowers the charge but cannot cut a running cooldown
        // short.
        if (!(state.depleted && isCooling(now) && !p.depleted)) {
            // A remote depletion arriving while this device is alive starts a dead period
            // here. While already dead, a later anchor with a different depletedAt is the
            // same period continuing, so the seq holds still.
            if (p.depleted && !state.depleted) state.depletionSeq += 1
            state.depleted = p.depleted
            state.depletedAt = p.depletedAt
        }
        state.lastTickTs = now
        if (engagedNow(now) == null) {
            state.remoteDraining = a.draining && !p.depleted
            state.remoteDrainingTs = a.asOf
        }
        recompute(sbNow())
        store.save(state)
        return AdoptResult(adopted = true)
    }

    /** What this device would publish right now. */
    fun getAnchor(): Anchor = synchronized(lock) {
        guardClock()
        val now = sbNow()
        recompute(now)
        Anchor(
            charge = state.charge,
            asOf = state.lastTickTs,
            // Only a local drain is advertised: republishing a mirrored remote one would
            // stamp a fresh write time on another device's drain.
            draining = !state.depleted && engagedNow(now) != null,
            depleted = state.depleted,
            depletedAt = state.depletedAt,
            writer = state.deviceId,
        )
    }

    /**
     * Move the clock anchor to [offsetMs], the new distance from device time to server time.
     * The jump moves sbNow and every stored timestamp was minted under the old anchor, so
     * the current period settles first and then they all cross together. Left alone the jump
     * reads as elapsed time: a device ten minutes behind the server would spend ten minutes
     * of charge on its first sync response.
     */
    fun applyServerOffset(offsetMs: Long) = synchronized(lock) { reoffset(offsetMs) }

    private fun reoffset(offsetMs: Long) {
        val delta = offsetMs - state.serverOffset
        if (delta == 0L) return
        guardClock()
        recompute(sbNow()) // close the period under the old anchor
        state.serverOffset = offsetMs
        shiftStamps(delta)
        // Not persisted, matching the extension: the offset moves with every response's
        // round trip jitter, so saving here would fsync the state file once per HTTP
        // response. Still consistent, since every stored stamp was minted under the stored
        // offset and the next real save carries the new anchor.
    }

    // --- Clock jumps -------------------------------------------------------------------

    /**
     * The device wall clock moved and the app layer knows it (Android's TIME_CHANGED, or a
     * time zone change). Settles the time that actually passed off the monotonic clock, then
     * carries every stored stamp across the jump so a running cooldown or pass keeps the
     * time it had left. Safe to call when nothing moved; the correction is then zero.
     */
    fun onWallClockChanged(): Snapshot = synchronized(lock) {
        reanchor(toleranceMs = 0)
        val now = sbNow()
        recompute(now)
        store.save(state)
        snapshotAt(now)
    }

    /**
     * The unattended version, run before every settle. Fires only when the wall and
     * monotonic clocks disagree by more than the tolerance, which is a jump nothing told us
     * about. Ordinary scheduling slop stays well inside it.
     */
    private fun guardClock() {
        if (guardClockJumps) reanchor(CLOCK_JUMP_TOLERANCE_MS)
    }

    private fun reanchor(toleranceMs: Long) {
        val mono = clock.monotonicNow()
        val baseline = lastMono
        lastMono = mono
        if (baseline == null) return // cold start: establish the baseline, correct nothing
        val jump = (sbNow() - state.lastTickTs) - max(0L, mono - baseline)
        if (abs(jump) > toleranceMs) shiftStamps(jump)
    }

    /** Carry every server anchored stamp across a jump of [delta] together. */
    private fun shiftStamps(delta: Long) {
        state.lastTickTs += delta
        state.depletedAt = state.depletedAt?.plus(delta)
        if (state.remoteDrainingTs != 0L) state.remoteDrainingTs += delta
        for (id in state.sitePasses.keys.toList()) {
            state.sitePasses[id] = state.sitePasses.getValue(id) + delta
        }
    }

    /** For tests asserting on fields the snapshot omits. Do not mutate. */
    internal fun stateForTest(): BatteryState = state

    private companion object {
        /** Wall/monotonic drift past this counts as a jump, not scheduling slop. */
        const val CLOCK_JUMP_TOLERANCE_MS = 2000L

        fun mintDeviceId(): String {
            val bytes = ByteArray(8)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
