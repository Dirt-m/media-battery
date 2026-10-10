package site.dirt23.battery.core.model

import site.dirt23.battery.core.Constants
import kotlin.math.max
import kotlin.math.min

/**
 * How one site or app is treated. The extension stores this loosely typed (`false` for off,
 * `"block"`, `true` or a missing key for tracked), so the wire mapping is pinned here.
 */
enum class SiteMode {
    ON,
    OFF,
    BLOCK;

    /** What the extension writes into `enabledSites`. */
    val wire: Any
        get() = when (this) {
            ON -> true
            OFF -> false
            BLOCK -> "block"
        }

    companion object {
        /** Anything but `false` or `"block"` collapses to tracked, as in the extension. */
        fun fromWire(v: Any?): SiteMode = when (v) {
            false -> OFF
            "block" -> BLOCK
            else -> ON
        }
    }
}

/**
 * A user added site. The engine only carries it through storage and names it in the
 * snapshot; host normalization, permissions and script registration live elsewhere.
 */
data class CustomEntry(val id: String, val name: String, val host: String)

/**
 * The persisted battery state, mirroring the extension's storage keys one for one. All var,
 * because this is the mutable cell the engine ticks; nothing outside the engine writes to it.
 *
 * Timestamps ([lastTickTs], [depletedAt], [remoteDrainingTs], every [sitePasses] expiry) are
 * server anchored, that is device wall time plus [serverOffset], and only ever move together
 * through the engine's re-anchoring. Shifting one and not the others reads as elapsed time
 * and spends real charge.
 */
data class BatteryState(
    var charge: Double = Constants.DEFAULT_CHARGE_SECONDS,
    var capacity: Double = Constants.DEFAULT_CAPACITY_SECONDS.toDouble(),
    var rechargePerMin: Double = Constants.DEFAULT_RECHARGE_PER_MIN,
    var warnSeconds: Int = Constants.DEFAULT_WARN_SECONDS,
    var siteModes: MutableMap<String, SiteMode> = mutableMapOf(),
    var customEntries: MutableList<CustomEntry> = mutableListOf(),
    var hourRules: List<HourRule> = emptyList(),
    var sitePasses: MutableMap<String, Long> = mutableMapOf(),
    var hideYtSidebar: Boolean = false,
    var showTimeLeft: Boolean = true,
    var frictionCount: Int = Constants.DEFAULT_FRICTION_COUNT,
    var depleted: Boolean = false,
    var depletedAt: Long? = null,
    var depletionSeq: Int = 0,
    var lastTickTs: Long = 0,
    var remoteDraining: Boolean = false,
    var remoteDrainingTs: Long = 0,
    var deviceId: String? = null,
    var syncCode: String? = null,
    var serverUrl: String? = null,
    var serverOffset: Long = 0,
    var settingsMeta: MutableMap<String, Long> = mutableMapOf(),
) {
    /** The mode declared for a site, or tracked when it was never declared. */
    fun modeOf(id: String): SiteMode = siteModes[id] ?: SiteMode.ON

    /**
     * Repair whatever came out of storage, mirroring the extension's load(). A corrupt file
     * must not brick the battery: a zero recharge rate leaves a dead battery dead forever,
     * and a depletion with no start time can never lift. Non numbers fall back to the
     * defaults, then everything clamps.
     *
     * [nowMs] is server anchored now, used only for a state that arrived with no tick stamp.
     */
    fun sanitize(nowMs: Long): BatteryState {
        if (lastTickTs <= 0L) lastTickTs = nowMs
        if (depleted && depletedAt == null) depletedAt = lastTickTs

        if (!capacity.isFinite()) capacity = Constants.DEFAULT_CAPACITY_SECONDS.toDouble()
        capacity = clamp(
            capacity,
            Constants.CAPACITY_MIN_SECONDS.toDouble(),
            Constants.CAPACITY_MAX_SECONDS.toDouble(),
        )
        if (!rechargePerMin.isFinite()) rechargePerMin = Constants.DEFAULT_RECHARGE_PER_MIN
        rechargePerMin = clamp(rechargePerMin, Constants.RECHARGE_PER_MIN_MIN, Constants.RECHARGE_PER_MIN_MAX)
        // 0 turns the warning off, so the floor is 0 and not the default.
        warnSeconds = min(WARN_SECONDS_MAX, max(0, warnSeconds))
        frictionCount = clampFrictionCount(frictionCount)
        charge = clamp(charge, 0.0, capacity)
        depletionSeq = max(0, depletionSeq)
        return this
    }

    companion object {
        const val WARN_SECONDS_MAX = 24 * 3600

        /** The extension's clamp: a non finite value lands on the low bound. */
        fun clamp(v: Double, lo: Double, hi: Double): Double =
            if (!v.isFinite()) lo else min(hi, max(lo, v))

        /** 1 to 5 whole questions, the extension's sanitizeFrictionCount. */
        fun clampFrictionCount(n: Int): Int =
            min(Constants.FRICTION_COUNT_MAX, max(Constants.FRICTION_COUNT_MIN, n))
    }
}
