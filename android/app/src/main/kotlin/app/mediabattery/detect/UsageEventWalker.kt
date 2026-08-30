package app.mediabattery.detect

import android.app.usage.UsageEvents

/**
 * What a usage event stream says about the phone, replayed in order.
 *
 * One walker, two callers: the live detector feeds it whatever the last poll returned, and
 * the replayer feeds it a whole missed stretch on service start. Two readings of the same
 * stream is how the live path and the catch up path start disagreeing.
 *
 * The foreground package is the last activity to resume. A pause clears it, which is right
 * at a real departure and slightly early inside one app (activity to activity pauses the
 * old one before resuming the new); the trailing debounce upstream absorbs that.
 */
class UsageEventWalker(
    var packageName: String? = null,
    var interactive: Boolean = true,
    var locked: Boolean = false,
) {
    /**
     * Feeds one event. What it moved is read off the walker afterwards, since the two
     * callers care about different parts of the reading.
     */
    fun feed(type: Int, pkg: String?, atMs: Long) {
        when (type) {
            UsageEvents.Event.ACTIVITY_RESUMED -> if (pkg != null) packageName = pkg
            UsageEvents.Event.ACTIVITY_PAUSED -> if (pkg != null && pkg == packageName) packageName = null
            UsageEvents.Event.SCREEN_INTERACTIVE -> interactive = true
            UsageEvents.Event.SCREEN_NON_INTERACTIVE -> interactive = false
            UsageEvents.Event.KEYGUARD_SHOWN -> locked = true
            UsageEvents.Event.KEYGUARD_HIDDEN -> locked = false
            else -> Unit
        }
    }

    /** True when a screen or lock fact is in this event, so a caller knows to re-read the system. */
    fun isScreenEvent(type: Int): Boolean = type == UsageEvents.Event.SCREEN_INTERACTIVE ||
        type == UsageEvents.Event.SCREEN_NON_INTERACTIVE ||
        type == UsageEvents.Event.KEYGUARD_SHOWN ||
        type == UsageEvents.Event.KEYGUARD_HIDDEN
}
