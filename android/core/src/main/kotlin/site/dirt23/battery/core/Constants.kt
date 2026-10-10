package site.dirt23.battery.core

// Wire and behavior constants shared with the Firefox extension. Changing one here
// without changing the extension there breaks cross-device agreement.
object Constants {
    /** Seconds useReserve adds. Not a setting. */
    const val RESERVE_SECONDS = 300

    /** Dead period after depletion. Not a setting: synced devices have to agree on its end. */
    const val COOLDOWN_SECONDS = 600

    /** Friction gated pass through a site/app block. */
    const val SITE_PASS_SECONDS = 300

    /** A draining anchor projects drain at most this far past its asOf. */
    const val DRAIN_HORIZON_MS = 90_000L

    /** Slack for sub-second projection noise when comparing a remote charge to the local one. */
    const val ADOPT_EPS_SECONDS = 2.0

    const val DEFAULT_CHARGE_SECONDS = 1800.0
    const val DEFAULT_CAPACITY_SECONDS = 1800
    const val DEFAULT_RECHARGE_PER_MIN = 5.0
    const val DEFAULT_WARN_SECONDS = 300

    /** Questions a friction gate asks (`frictionCount`), one flat toll for every gate. */
    const val DEFAULT_FRICTION_COUNT = 1
    const val FRICTION_COUNT_MIN = 1
    const val FRICTION_COUNT_MAX = 5

    const val CAPACITY_MIN_SECONDS = 60
    const val CAPACITY_MAX_SECONDS = 24 * 3600
    const val RECHARGE_PER_MIN_MIN = 1.0
    const val RECHARGE_PER_MIN_MAX = 600.0
}
