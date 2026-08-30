package app.mediabattery.block

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Decides when the cover goes up and when it comes down.
 *
 * Three states: nothing, a short wait, and up. Going up waits 150ms, so an app the user is
 * only passing through is never covered at all. Coming down never waits, and most of the
 * signals reach [BlockActivity] directly through the snapshot it watches.
 *
 * What the page cannot see for itself is the user walking off somewhere else. Once the cover
 * is in front, the app in front is the cover, so the decision reads None whether the user is
 * still stuck behind it or long gone. Hence [onForeground]. The `SWEEP_MS` wait before the
 * card goes is what tells a departure from a second blocked app, which produces the same
 * foreground change and then a fresh decision; cancelling the sweep on that decision reuses
 * the one cover task instead of tearing it down and rebuilding it a moment later.
 *
 * The home screen is deliberately not a departure. Opening the switcher and going home look
 * the same from here (both resume the launcher on most phones), and the cover's card is meant
 * to survive a swipe up, so it outlives a trip home and goes on the next app the user opens.
 */
class BlockController(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    /** One intent, reused. Only the id ever changes. See [BlockActivity.intent]. */
    private val cover = BlockActivity.intent(context)

    private val self = context.packageName
    private val homes: Set<String> by lazy { homePackages() }

    private var current: BlockDecision = BlockDecision.None
    private var showingFor: String? = null

    /** True when the app in front is somewhere the cover has no business standing over. */
    private var away = false

    private var pending: Job? = null
    private var sweep: Job? = null

    /** What was covered last and when it went, so a quick return skips the settle. */
    private var lastClearedKey: String? = null
    private var lastClearedAt = -RECOVER_MS

    /** One window back, so the first cover after a boot is never mistaken for one in flight. */
    private var launchedAt = -ARRIVAL_MS

    /** The current decision. Anything but None means a cover is due. */
    fun update(next: BlockDecision) {
        if (next == BlockDecision.None) {
            clear()
            return
        }
        cancelSweep()
        current = next

        val key = keyOf(next)
        // Already handled: the cover is up for this story, or it was asked for recently
        // enough that it may still be on its way. Past that window the ask is repeated,
        // which is also how a launch the system refused heals itself.
        if (key == showingFor && (BlockActivity.isShowing || pending != null || arriving())) return

        showingFor = key
        if (pending != null) return
        // A cover asked for again right after going down relaunches with no settle. That is
        // also what meets the covered app coming back in front of its own cover: taking the
        // cover's switcher card is what made the decision None and cleared this key a moment
        // ago, so the app behind it is re covered on the spot.
        val instant = key == lastClearedKey &&
            SystemClock.elapsedRealtime() - lastClearedAt < RECOVER_MS
        pending = scope.launch {
            if (!instant) delay(SETTLE_MS)
            pending = null
            launchedAt = SystemClock.elapsedRealtime()
            show(current)
        }
    }

    /**
     * Who is in front, straight off the detectors; only the cover's own fate rides on it.
     * What it means for the battery arrives as a decision like everything else.
     *
     * Read here, on the thread that reported it, so the launcher lookup never lands on the
     * main thread the first time someone walks out of a covered app.
     */
    fun onForeground(pkg: String?) {
        val elsewhere = pkg != null && pkg != self && pkg !in homes
        scope.launch {
            away = elsewhere
            armSweep()
        }
    }

    fun stop() {
        clear()
        cancelSweep()
        // The service is going away, and a cover it cannot lift is stuck: the countdown
        // would freeze and the gate would never be answered.
        BlockActivity.dismiss()
    }

    private fun clear() {
        pending?.cancel()
        pending = null
        showingFor?.let {
            lastClearedKey = it
            lastClearedAt = SystemClock.elapsedRealtime()
        }
        showingFor = null
        current = BlockDecision.None
        armSweep()
    }

    private fun show(decision: BlockDecision) {
        if (decision == BlockDecision.None) return
        cover.putExtra(BlockActivity.EXTRA_ID, (decision as? BlockDecision.AppBlocked)?.id)
        runCatching { context.startActivity(cover) }
    }

    /** True while a just launched cover has not had time to report itself on screen. */
    private fun arriving(): Boolean = SystemClock.elapsedRealtime() - launchedAt < ARRIVAL_MS


    /**
     * Arms the sweep when nothing is due and the user is somewhere else. Nothing in front is
     * the moment between two apps or the switcher itself, and the launcher is where the
     * switcher lives, so neither takes the card down.
     */
    private fun armSweep() {
        if (sweep != null || showingFor != null || !away) return
        sweep = scope.launch {
            delay(SWEEP_MS)
            sweep = null
            BlockActivity.dismiss()
        }
    }

    private fun cancelSweep() {
        sweep?.cancel()
        sweep = null
    }

    /** Every launcher on the phone. Declared in the manifest's `queries`, so this resolves. */
    private fun homePackages(): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return runCatching {
            context.packageManager.queryIntentActivities(home, 0)
                .mapTo(LinkedHashSet()) { it.activityInfo.packageName }
        }.getOrDefault(emptySet())
    }

    private fun keyOf(decision: BlockDecision): String = when (decision) {
        is BlockDecision.Dead -> "dead"
        is BlockDecision.AppBlocked -> "app:${decision.id}"
        BlockDecision.None -> ""
    }

    private companion object {
        const val SETTLE_MS = 150L

        /** How long a launch gets to land before the ask is repeated. */
        const val ARRIVAL_MS = 2_000L

        /** Long enough for a second blocked app's decision to arrive and cancel it. */
        const val SWEEP_MS = 400L

        /** A cover asked for again this soon after going down relaunches with no settle. */
        const val RECOVER_MS = 30_000L
    }
}
