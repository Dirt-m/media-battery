package site.dirt23.battery.core.engine

import site.dirt23.battery.core.SbClock
import site.dirt23.battery.core.model.BatteryState
import site.dirt23.battery.core.model.HourRule
import site.dirt23.battery.core.model.RuleAction
import site.dirt23.battery.core.model.RuleScope
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Shared scaffolding for the engine suites. The extension's tests inject time by writing
 * `lastTickTs` and reading the machine clock; here the clock itself is injectable, so the
 * assertions hold on any machine in any zone.
 */

internal val AMS: ZoneId = ZoneId.of("Europe/Amsterdam")

/** Midday, so a window two hours either side of it stays on one day. */
internal val T0: Long = ZonedDateTime.of(2026, 6, 10, 12, 0, 0, 0, AMS).toInstant().toEpochMilli()

/**
 * [advance] is time passing. [jumpWall] is someone setting the clock, which is what the
 * engine's guard is for.
 */
internal class FakeClock(
    var wall: Long = T0,
    var mono: Long = 0,
    var zoneId: ZoneId = AMS,
) : SbClock {
    override fun wallNow(): Long = wall

    override fun monotonicNow(): Long = mono

    override fun zone(): ZoneId = zoneId

    fun advance(ms: Long) {
        wall += ms
        mono += ms
    }

    fun jumpWall(ms: Long) {
        wall += ms
    }
}

internal class MemoryStateStore(private var current: BatteryState? = null) : StateStore {
    var saves = 0
        private set

    override fun load(): BatteryState? = current

    override fun save(s: BatteryState) {
        saves++
        current = s
    }
}

/** A known good baseline; each test overrides only what it is about. */
internal fun baseState(): BatteryState = BatteryState(
    charge = 900.0,
    capacity = 1800.0,
    rechargePerMin = 5.0,
    lastTickTs = T0,
    deviceId = "me",
)

/** An engine on a stepped clock, plus the state it is ticking. */
internal class Rig(
    guardClockJumps: Boolean = false,
    seed: BatteryState.() -> Unit = {},
) {
    val clock = FakeClock()
    val store = MemoryStateStore(baseState().apply(seed))
    val engine = BatteryEngine(store, clock, guardClockJumps)

    /** The live state. Tests assert on fields the snapshot deliberately leaves out. */
    val s: BatteryState get() = engine.stateForTest()

    fun advance(ms: Long) = clock.advance(ms)
}

/** "HH:MM" on the device wall clock, the shape a rule stores. */
internal fun hm(millis: Long): String {
    val z = Instant.ofEpochMilli(millis).atZone(AMS)
    return "%02d:%02d".format(z.hour, z.minute)
}

/** A window open right now: [before] minutes back to [after] minutes ahead of [T0]. */
internal fun window(
    before: Long,
    after: Long,
    action: RuleAction,
    scope: RuleScope = RuleScope.ALL,
    sites: List<String> = emptyList(),
    percent: Int = 0,
    minutes: Int = 0,
): HourRule = HourRule(
    id = "hr1",
    from = hm(T0 - before * 60_000),
    to = hm(T0 + after * 60_000),
    action = action,
    scope = scope,
    sites = sites,
    percent = percent,
    minutes = minutes,
)
