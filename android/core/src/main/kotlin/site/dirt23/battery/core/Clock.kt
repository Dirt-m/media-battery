package site.dirt23.battery.core

import java.time.ZoneId

/**
 * The three time questions the engine asks, behind one seam so tests can step time.
 *
 * [wallNow] is what stored timestamps are minted against, but it can jump (NTP, a manual
 * set, a time zone change). [monotonicNow] only counts forward, and answers how much time
 * actually passed when the two disagree.
 *
 * [monotonicNow] must keep counting through device suspend, which rules out
 * `System.nanoTime()`: it is CLOCK_MONOTONIC, which halts while the device sleeps, so a
 * slept-through night reads as a wall clock jump and the guard cancels the night's
 * recharge. Android's suspend-counting clock is `SystemClock.elapsedRealtime()`, hence
 * the real implementation living in the app module.
 *
 * [zone] is a live input: hour rules run on the wall clock the user reads, so read it per
 * evaluation and never cache it.
 */
interface SbClock {
    /** Milliseconds since the epoch on the device's own clock. */
    fun wallNow(): Long

    /**
     * A monotonically increasing millisecond counter with an arbitrary origin, counting
     * through suspend.
     */
    fun monotonicNow(): Long

    /** The device's current time zone. */
    fun zone(): ZoneId
}
