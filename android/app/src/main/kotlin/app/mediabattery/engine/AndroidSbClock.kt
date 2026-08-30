package app.mediabattery.engine

import android.os.SystemClock
import site.dirt23.battery.core.SbClock
import java.time.ZoneId

/**
 * Monotonic here is [SystemClock.elapsedRealtime], not `System.nanoTime()`: nanoTime halts
 * in deep sleep, so a gap slept through reads as a wall clock jump and the engine's guard
 * cancels its recharge. elapsedRealtime counts suspend, which is what
 * [SbClock.monotonicNow] requires.
 */
object AndroidSbClock : SbClock {
    override fun wallNow(): Long = System.currentTimeMillis()

    override fun monotonicNow(): Long = SystemClock.elapsedRealtime()

    override fun zone(): ZoneId = ZoneId.systemDefault()
}
