package app.mediabattery.detect

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Who is in front, read off `UsageStatsManager`.
 *
 * There is no callback for this on Android, so it is a poll, paced by what is at stake.
 * While a tracked app is in front, or while a cover is up, a late answer costs charge or
 * leaves a cover over an innocent app, so the band is 300ms; the rest of the time 2s is
 * plenty. The loop stops entirely while the screen is off, and the events are still there
 * to read when it comes back.
 *
 * Windows are tracked by their end (`lastQueryEnd`) and joined end to start, so no stretch
 * is read twice and none is skipped. Each pass rewinds the window a second because the log
 * is written slightly behind real time; events already seen are dropped by timestamp.
 *
 * [reconcile] lets a caller that suspects a stale answer force a pass now, restating who is
 * in front whether or not the log moved.
 */
class UsageStatsForegroundDetector(
    context: Context,
    private val scope: CoroutineScope,
    private val screen: ScreenStateMonitor,
    /** True while a late answer would cost something: a tracked app in front, or a cover up. */
    private val hot: () -> Boolean,
) : ForegroundDetector {
    private val usage = context.getSystemService(UsageStatsManager::class.java)
    private val walker = UsageEventWalker()
    private var job: Job? = null
    private var lastQueryEnd = 0L
    private var lastEventTs = 0L

    /** Conflated: several nudges inside one band make one extra pass, not a queue. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var restate = false

    override fun start(listener: ForegroundDetector.Listener) {
        if (job != null) return
        lastQueryEnd = System.currentTimeMillis() - SEED_LOOKBACK_MS
        job = scope.launch(Dispatchers.Default) {
            while (isActive) {
                // Nothing moves in front of a dark screen; wait for it rather than spin.
                if (!screen.interactive.value) screen.interactive.first { it }
                val forced = restate
                restate = false
                poll(listener, forced)
                withTimeoutOrNull(if (hot()) HOT_BAND_MS else COLD_BAND_MS) { wake.receive() }
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    /** The package in front as of the last poll. */
    fun current(): String? = walker.packageName

    /**
     * Read the log now, outside the band, and say who is in front even if that repeats the
     * last answer. Safe from any thread; all it does is wake the loop.
     */
    fun reconcile() {
        restate = true
        wake.trySend(Unit)
    }

    private fun poll(listener: ForegroundDetector.Listener, restate: Boolean) {
        val usm = usage ?: return
        val now = System.currentTimeMillis()
        val begin = minOf(lastQueryEnd - OVERLAP_MS, now)
        val events = runCatching { usm.queryEvents(begin, now) }.getOrNull() ?: return
        lastQueryEnd = now

        val event = UsageEvents.Event()
        var sawScreenEvent = false
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val ts = event.timeStamp
            if (ts < lastEventTs) continue
            lastEventTs = ts
            if (walker.isScreenEvent(event.eventType)) sawScreenEvent = true
            val before = walker.packageName
            walker.feed(event.eventType, event.packageName, ts)
            if (walker.packageName != before) listener.onForeground(walker.packageName, ts)
        }
        // The stream and the system can disagree for a moment after a lock or a screen
        // change. The system decides, so re-read it rather than trust the log.
        if (sawScreenEvent) screen.refresh(now)
        // A forced pass answers even when the log said nothing: the caller asked what is
        // true now, not what changed.
        if (restate) listener.onForeground(walker.packageName, now)
    }

    private companion object {
        const val HOT_BAND_MS = 300L
        const val COLD_BAND_MS = 2000L
        const val OVERLAP_MS = 1000L

        /** Enough to pick up who is in front right now; the deep catch up is the replayer's job. */
        const val SEED_LOOKBACK_MS = 10_000L
    }
}
