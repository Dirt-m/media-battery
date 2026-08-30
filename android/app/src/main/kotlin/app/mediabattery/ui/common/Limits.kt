package app.mediabattery.ui.common

import site.dirt23.battery.core.Constants
import site.dirt23.battery.core.rules.Rules

/**
 * What the settings controls may step to, in the units the controls work in. One place for
 * all of them, so a control cannot offer a value the engine clamps back on save. Both
 * capacity spinners (the battery's own and a capacity rule's) run on the same minutes.
 */
internal object Limits {
    /** Capacity in whole minutes, off the engine's own clamp. */
    const val CAP_MIN = Constants.CAPACITY_MIN_SECONDS / 60
    const val CAP_MAX = Constants.CAPACITY_MAX_SECONDS / 60

    /** Five minutes a tap. */
    const val CAP_STEP = 5

    /** What Rules.sanitizeRules keeps. Adding past it would silently drop the extras. */
    const val MAX_RULES = Rules.MAX_RULES
}
