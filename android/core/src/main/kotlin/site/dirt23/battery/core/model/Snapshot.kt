package site.dirt23.battery.core.model

// The read model the UI renders, mirroring the extension's snapshot(). The gauge runs on
// effCapacity (the cap that holds right now, lowered by an open capacity window); a
// mirrored remote drain is draining with a null drainingId.
data class Snapshot(
    val charge: Double,
    val capacity: Int,
    val effCapacity: Int,
    val rechargePerMin: Double,
    val warnSeconds: Int,
    val showTimeLeft: Boolean = true,
    /** Questions every friction gate asks. */
    val frictionCount: Int = 1,
    val reserveSeconds: Int,
    val passSeconds: Int,
    val rechargePaused: Boolean,
    val cooldownRemaining: Double,
    val depleted: Boolean,
    val depletedAt: Long?,
    val depletionSeq: Int,
    val draining: Boolean,
    val drainingId: String?,
    val drainingName: String?,
    // Seconds remaining, not expiry stamps: the UI only needs to know how much longer.
    val sitePasses: Map<String, Int> = emptyMap(),
)
