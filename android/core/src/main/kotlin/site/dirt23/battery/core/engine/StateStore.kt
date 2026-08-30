package site.dirt23.battery.core.engine

import site.dirt23.battery.core.model.BatteryState

/**
 * Where the battery state lives between ticks. The engine calls this synchronously under
 * its own lock, so implementations must be fast and must not call back into the engine.
 *
 * [load] returns null for both "nothing stored" and "stored but unreadable"; the engine
 * boots the defaults either way rather than refuse to start.
 */
interface StateStore {
    fun load(): BatteryState?

    fun save(s: BatteryState)
}
