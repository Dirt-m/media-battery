package site.dirt23.battery.core.model

/**
 * The shared charge anchor devices sync: what the charge was, when that was true, and
 * whether it was falling. Timestamps are server time so clock skew cannot corrupt the
 * delta math.
 *
 * [writer] is the per install id of the device that wrote it, used only so the engine
 * never adopts its own echo. The synced blob carries more; the rest is the sync layer's.
 */
data class Anchor(
    val charge: Double,
    val asOf: Long,
    val draining: Boolean = false,
    val depleted: Boolean = false,
    val depletedAt: Long? = null,
    val writer: String? = null,
)
