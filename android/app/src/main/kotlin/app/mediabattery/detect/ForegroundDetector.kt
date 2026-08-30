package app.mediabattery.detect

/**
 * Who is in front. One seam for the fast path and the usage poll, so the supervisor never
 * has to care which of them answered.
 *
 * The package is null when nothing identifiable is in front (the launcher counts as an app
 * and arrives by name; a screen that went off arrives as null). Every report carries the
 * moment it happened rather than the moment it was noticed: a poll can be up to a band late
 * and the engine attributes time to the timestamp it is given.
 */
interface ForegroundDetector {
    fun interface Listener {
        fun onForeground(packageName: String?, atMs: Long)
    }

    fun start(listener: Listener)

    fun stop()
}
