package app.mediabattery.detect

import kotlinx.coroutines.flow.StateFlow

/**
 * A detector that is told who is in front instead of polling for it.
 *
 * The usage event log has no callback, so reading it is always a little late. A push based
 * detector answers in milliseconds, which decides whether the cover is already there when
 * the app draws. Optional in every sense: the user grants it, the build may not have it at
 * all, and it can go away at runtime, so [connected] is a state and not a constructor
 * argument.
 *
 * [setTracked] exists because such a detector is usually filtered by package for its own
 * cost, and the filter has to follow the user's picks.
 */
interface FastForegroundDetector : ForegroundDetector {
    /** True while the detector is actually live and reporting. */
    val connected: StateFlow<Boolean>

    /** The packages the user tracks right now. */
    fun setTracked(packages: Set<String>)
}
