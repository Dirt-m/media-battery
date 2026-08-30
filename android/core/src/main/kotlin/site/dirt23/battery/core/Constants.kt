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

    /**
     * Seconds a stop costs while sync is on. A follower mirrors this device's drain and
     * keeps draining through the detection and push latency, so an honest stopped anchor
     * lands a few seconds above the mirror and the extension (through 1.7.1) rejects it
     * without ending the mirror. Settling the stop to the push moment and paying this on
     * top puts the anchor at or below the mirror. Remove once the extension ends the
     * mirror on a rejected stop; the phone side of that fix is already in [BatteryEngine].
     */
    const val GOODBYE_PAD_SECONDS = 2.0

    const val DEFAULT_CHARGE_SECONDS = 1800.0
    const val DEFAULT_CAPACITY_SECONDS = 1800
    const val DEFAULT_RECHARGE_PER_MIN = 5.0
    const val DEFAULT_WARN_SECONDS = 300

    const val CAPACITY_MIN_SECONDS = 60
    const val CAPACITY_MAX_SECONDS = 24 * 3600
    const val RECHARGE_PER_MIN_MIN = 1.0
    const val RECHARGE_PER_MIN_MAX = 600.0
}
