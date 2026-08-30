package app.mediabattery.detect

import app.mediabattery.data.TrackedAppsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Merges the fast detector and the usage poll into one foreground answer.
 *
 * The fast detector, when granted, owns the enter edge: it is pushed the window change in
 * milliseconds, which is what makes a cover land before the app has drawn. It also reports
 * the launcher and recents, since a package filtered service is never told about the app
 * the user left to.
 *
 * The usage event log is still the authority. It cannot be turned off behind our back and
 * it survives a service Android unbinds, so it keeps polling and its answer is the one that
 * holds. While the fast path is live the log drops its hot band (see the `hot` lambda it is
 * built with), since all that is left for it is catching a leave the fast path missed.
 *
 * The lease covers a fast path that died quietly: if a tracked app is believed to be in
 * front, the screen is on, and neither detector has said anything for fifteen seconds, the
 * log is read again and reported even if it repeats the current answer.
 */
class DetectorSupervisor(
    private val usage: UsageStatsForegroundDetector,
    private val fast: FastForegroundDetector?,
    private val tracked: TrackedAppsStore,
    private val screen: ScreenStateMonitor,
    private val scope: CoroutineScope,
) : ForegroundDetector {
    private val lock = Any()
    private val jobs = mutableListOf<Job>()

    private var out: ForegroundDetector.Listener? = null
    private var current: String? = null
    private var currentAtMs = 0L
    private var lastReportMs = 0L

    override fun start(listener: ForegroundDetector.Listener) {
        if (out != null) return
        out = listener
        lastReportMs = System.currentTimeMillis()

        usage.start(::report)
        // A service restart in a live process keeps the walker's state, so a user still
        // sitting in the same app would never be re-reported and this supervisor would
        // believe nothing is in front. The full flavor used to get this from the connected
        // flow's first emission below; the play flavor got nothing.
        usage.reconcile()
        fast?.let { detector ->
            detector.start(::report)
            // The allowed set, not the raw picks: no point listening for a package the
            // guard refuses.
            jobs += scope.launch { tracked.packages.collect { detector.setTracked(tracked.allowed()) } }
            // Both edges: a detector that just connected knows nothing until the next window
            // change, and one that just went away may have been wrong for a while.
            jobs += scope.launch { detector.connected.collect { usage.reconcile() } }
        }
        jobs += scope.launch(Dispatchers.Default) { lease() }
    }

    override fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        fast?.stop()
        usage.stop()
        out = null
        synchronized(lock) {
            current = null
            currentAtMs = 0L
        }
    }

    /**
     * Both detectors report here, from their own threads. A repeat of the answer we already
     * hold is dropped, and a moment earlier than the one already reported is pulled forward:
     * the engine attributes time to the timestamp it is handed, so two detectors with
     * different lags must not walk it backwards.
     */
    private fun report(pkg: String?, atMs: Long) {
        val listener = out ?: return
        var at = atMs
        synchronized(lock) {
            lastReportMs = System.currentTimeMillis()
            if (pkg == current) return
            at = maxOf(atMs, currentAtMs)
            current = pkg
            currentAtMs = at
        }
        listener.onForeground(pkg, at)
    }

    private suspend fun lease() {
        while (scope.isActive) {
            // Park while the screen is off rather than tick a five second no-op all night.
            if (!screen.interactive.value) screen.interactive.first { it }
            delay(LEASE_CHECK_MS)
            var quiet = 0L
            var pkg: String? = null
            synchronized(lock) {
                pkg = current
                quiet = System.currentTimeMillis() - lastReportMs
            }
            val believed = pkg ?: continue
            if (quiet < LEASE_MS) continue
            if (!tracked.isTracked(believed)) continue
            if (!screen.interactive.value) continue
            usage.reconcile()
        }
    }

    private companion object {
        const val LEASE_MS = 15_000L
        const val LEASE_CHECK_MS = 5_000L
    }
}
