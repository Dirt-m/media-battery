package site.dirt23.battery.core.sync

import kotlinx.serialization.json.JsonElement
import site.dirt23.battery.core.engine.AdoptResult
import site.dirt23.battery.core.model.Anchor

/** What the sync engine is doing, as the UI reads it. */
enum class SyncState { OFF, SYNCING, SYNCED, OFFLINE }

/**
 * The status the engine publishes. [error] is a short machine tag, not copy: `auth` when the
 * server rejected the token, otherwise whatever the failed request said.
 */
data class SyncStatus(
    val on: Boolean = false,
    val state: SyncState = SyncState.OFF,
    val lastSyncTs: Long = 0,
    val error: String? = null,
)

/**
 * The sync engine's persisted state, all of it per device. [syncCode] is the secret
 * everything else derives from.
 */
data class SyncConfig(
    /** The 26 character code, or null when sync is off. */
    val syncCode: String? = null,
    /** Where to sync, or null for the default server. */
    val serverUrl: String? = null,
    /** Milliseconds to add to device time to reach server time. */
    val serverOffset: Long = 0,
    /** Per settings key, when it was last written anywhere. The merge runs on these. */
    val settingsMeta: Map<String, Long> = emptyMap(),
    /**
     * Settings keys this device does not maintain, held verbatim so a push re-emits them.
     * Persisted rather than kept in memory: a device that restarted and pushed before its
     * first pull would erase another platform's settings from the server.
     */
    val foreignSettings: Map<String, SettingsEntry> = emptyMap(),
)

/** A partial config write: only the fields that are set are stored. */
data class ConfigPatch(
    val syncCode: String? = null,
    /** Set the code to null, since a null [syncCode] means leave it alone. */
    val clearSyncCode: Boolean = false,
    val serverUrl: String? = null,
    val serverOffset: Long? = null,
    val settingsMeta: Map<String, Long>? = null,
    val foreignSettings: Map<String, SettingsEntry>? = null,
)

/**
 * The one bridge between the sync engine and the battery, ported from the extension's
 * syncAdapter (background.js:732). The engine holds no battery state and does no time math of
 * its own; every rule about what a remote value may do lives on the other side of this, which
 * keeps lower-wins checkable in one place.
 */
interface SyncAdapter {

    /** The current sync config. Read fresh every time; the engine caches none of it. */
    fun getConfig(): SyncConfig

    /**
     * Persist the fields [patch] sets. [ConfigPatch.serverOffset] must not land as a plain
     * assignment: it moves the clock anchor, so it goes through the same re-anchor as
     * [setServerOffset], or the jump reads as elapsed time.
     */
    fun saveConfig(patch: ConfigPatch)

    /**
     * What this device would publish right now. Implementations settle the charge first, so
     * `asOf` is the moment the anchor was built, not the last tick.
     *
     * Only a local drain may be advertised as draining: republishing a drain mirrored from
     * another device stamps a fresh write time on it and keeps it alive forever.
     */
    fun getAnchor(): Anchor

    /**
     * Offer a remote anchor. Lower charge wins: the implementation adopts only if the anchor
     * projects to a charge at or below this device's.
     *
     * [AdoptResult.overwrite] tells the engine to write this device's lower value back to the
     * server. False while the rejected anchor is a live draining writer, since overwriting
     * then would just ping-pong with the device in use.
     */
    fun maybeAdoptAnchor(anchor: Anchor): AdoptResult

    /**
     * The settings this device maintains, as they travel. Values are [JsonElement] so numbers
     * render the way JavaScript renders them (see [JsWire]). Keys this device does not
     * maintain are absent; the engine holds those itself.
     */
    fun getSettings(): Map<String, JsonElement>

    /**
     * Settings merged in from another device. The map may carry keys this device knows
     * nothing about; ignore them, the engine has already stored them for re-emission.
     */
    fun applyRemoteSettings(values: Map<String, JsonElement>)

    /** Is the user in a tracked app right now? Decides who owns the shared charge. */
    fun isEngaged(): Boolean

    /** Server anchored now: device wall time plus [SyncConfig.serverOffset]. */
    fun sbNow(): Long

    /** The device's own wall clock, unanchored, for computing an offset from a server stamp. */
    fun deviceNow(): Long

    /**
     * Adopt [offsetMs] as the new absolute distance from device time to server time. Every
     * stored timestamp was minted under the old anchor and has to cross with it.
     */
    fun setServerOffset(offsetMs: Long)

    /** Publish the sync status for the UI. Called on every state change. */
    fun setStatus(status: SyncStatus)

    /**
     * Whether this device is the writer right now. The browser adapter uses it to swap a
     * throttled setInterval for a browser.alarms wake; Android's service heartbeat runs
     * regardless and ignores it, since [SyncEngine.onHeartbeat] re-checks engagement itself.
     */
    fun setHeartbeat(on: Boolean)
}
