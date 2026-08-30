package app.mediabattery.data

import android.content.Context

/**
 * The few device local flags that are not battery state.
 *
 * They live in device protected storage so the boot receiver can read them before the
 * first unlock. The battery state itself cannot move there: it is in `filesDir`, which is
 * credential protected and unreadable until the user unlocks, which is also why the
 * service is not started before then.
 */
class AppPrefs(context: Context) {
    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    /** Has the user been through the permission steps? Until then nothing runs. */
    var onboarded: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDED, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDED, value).apply()

    /**
     * Wall clock of the last settle the app knows about, used as the replay window's start
     * so a stretch the service was dead for gets read back out of the usage event log. The
     * engine keeps the same stamp internally but does not publish it; this one only needs to
     * be a floor, so a stale value costs a slightly longer replay and nothing else.
     */
    var lastSeenMs: Long
        get() = prefs.getLong(KEY_LAST_SEEN, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SEEN, value).apply()

    private companion object {
        const val KEY_ONBOARDED = "onboarded"
        const val KEY_LAST_SEEN = "lastSeenMs"
    }
}
