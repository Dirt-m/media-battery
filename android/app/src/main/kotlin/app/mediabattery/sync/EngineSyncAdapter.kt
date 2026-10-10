package app.mediabattery.sync

import app.mediabattery.data.TrackedAppsStore
import app.mediabattery.engine.EngineHost
import app.mediabattery.engine.SettingsView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import site.dirt23.battery.core.SbClock
import site.dirt23.battery.core.engine.AdoptResult
import site.dirt23.battery.core.engine.BatteryEngine
import site.dirt23.battery.core.engine.SettingsPatch
import site.dirt23.battery.core.identity.AppCatalog
import site.dirt23.battery.core.model.Anchor
import site.dirt23.battery.core.model.HourRule
import site.dirt23.battery.core.model.RawHourRule
import site.dirt23.battery.core.model.RuleAction
import site.dirt23.battery.core.model.SiteMode
import site.dirt23.battery.core.model.Snapshot
import site.dirt23.battery.core.sync.ConfigPatch
import site.dirt23.battery.core.sync.JsWire
import site.dirt23.battery.core.sync.SyncAdapter
import site.dirt23.battery.core.sync.SyncConfig
import site.dirt23.battery.core.sync.SyncStatus
import kotlin.math.roundToInt

/**
 * The bridge between the sync engine and the battery on Android, kept thin.
 *
 * Every rule about what a remote value may do lives in [BatteryEngine] (lower charge always
 * wins, a sync never raises the charge, a jump in the clock anchor carries every stored
 * timestamp with it), and every rule about what a device may forget lives in core's
 * SettingsWire. What is left here is plumbing plus the one thing this platform owns: which
 * settings it speaks, and in what words.
 *
 * The device id is the engine's. It mints one on first run and puts it on every anchor, so a
 * device never adopts its own echo.
 */
class EngineSyncAdapter(
    private val engine: BatteryEngine,
    private val host: EngineHost,
    private val store: SyncStore,
    private val tracked: TrackedAppsStore,
    private val clock: SbClock,
    initialServerOffset: Long,
) : SyncAdapter {

    /**
     * The server clock offset. The engine persists it, since it shifts with every other
     * stored timestamp; this is a cache of what the engine holds, seeded from the state file.
     */
    @Volatile
    private var offset: Long = initialServerOffset

    private val _status = MutableStateFlow(SyncStatus())

    /** What sync is doing, for the gauge's cloud and the sync screen. */
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    override fun getConfig(): SyncConfig = store.record.value.let {
        SyncConfig(
            syncCode = it.syncCode,
            serverUrl = it.serverUrl,
            serverOffset = offset,
            settingsMeta = it.settingsMeta,
            foreignSettings = it.foreignSettings,
        )
    }

    override fun saveConfig(patch: ConfigPatch) {
        // The offset moves the clock anchor, so it never lands as a plain assignment.
        patch.serverOffset?.let { setServerOffset(it) }
        store.update { r ->
            r.copy(
                syncCode = if (patch.clearSyncCode) null else patch.syncCode ?: r.syncCode,
                serverUrl = patch.serverUrl ?: r.serverUrl,
                settingsMeta = patch.settingsMeta ?: r.settingsMeta,
                foreignSettings = patch.foreignSettings ?: r.foreignSettings,
                // Leaving a profile leaves its blobs too: a later join must not re-emit the
                // old profile's customSites into the new one.
                passengers = if (patch.clearSyncCode) Passengers() else r.passengers,
            )
        }
    }

    override fun getAnchor(): Anchor = engine.getAnchor()

    override fun maybeAdoptAnchor(anchor: Anchor): AdoptResult {
        val result = engine.maybeAdoptAnchor(anchor)
        // The charge moved under the reading. Nothing else would notice until the next tick,
        // which on an idle phone is five seconds of a stale gauge.
        if (result.adopted) host.refresh()
        return result
    }

    override fun getSettings(): Map<String, JsonElement> =
        SettingsBridge.emit(
            host.snapshot.value,
            host.settings.value,
            store.record.value.passengers,
            tracked.packages.value,
        )

    override fun applyRemoteSettings(values: Map<String, JsonElement>) {
        val arrived = SettingsBridge.ingest(
            values,
            host.settings.value.siteModes,
            store.record.value.passengers,
        )
        store.update { it.copy(passengers = arrived.passengers) }
        host.applyRemoteSettings(arrived.patch)
        // The list is the host's input, not the engine's state, so it lands beside the
        // patch and the running engagement re-evaluates against it at once.
        arrived.trackedApps?.let { if (tracked.replace(it)) host.onTrackedChanged() }
    }

    /** Is the user in an app whose time counts right now? Decides who owns the charge. */
    override fun isEngaged(): Boolean = host.snapshot.value.drainingId != null

    override fun sbNow(): Long = clock.wallNow() + offset

    override fun deviceNow(): Long = clock.wallNow()

    override fun setServerOffset(offsetMs: Long) {
        offset = offsetMs
        engine.applyServerOffset(offsetMs)
    }

    override fun setStatus(status: SyncStatus) {
        _status.value = status
    }

    override fun setHeartbeat(on: Boolean) {
        // On the browser this swaps a throttled setInterval for an alarm that fires anyway.
        // Here the service heartbeat runs regardless and onHeartbeat re-checks engagement
        // itself, so there is nothing to arm.
    }
}

/**
 * The settings this device speaks, in the extension's words.
 *
 * Pure and separate from the adapter, because the wire shapes are the part the tests pin: a
 * settings document that disagrees about how a number or a rule is written is a different
 * document every time it crosses platforms.
 *
 * Three of the ten keys are passengers, held verbatim (see [Passengers]). The fourth
 * subtlety is `enabledSites`: one key holding every site's mode, so last write wins takes the
 * whole map. Two rules keep that from losing anything.
 *
 * Every id that arrives is kept, whether or not this phone has an app for it, so a push from
 * here cannot prune the desktop's sites.
 *
 * `app:` ids ride the wire like everything else, so two Android devices on one profile share
 * their app modes. This leans on the extension's save path carrying ids it has no row for
 * verbatim (options.js collectSites); an older extension rebuilt the map from the sites it
 * knew, and a pruned mode reads as tracked on the way back, which is why these used to stay
 * device local. Which apps are tracked syncs too, as `trackedApps`: the whole package list,
 * last write wins, so a reinstalled Media Battery or a second phone on the profile picks the
 * same apps back up. The extension holds that key as a passenger of its own.
 */
internal object SettingsBridge {

    /**
     * What arriving settings mean here: a patch for the engine, what to carry on, and the
     * tracked package list when the wire named one.
     */
    data class Arrived(
        val patch: SettingsPatch,
        val passengers: Passengers,
        val trackedApps: Set<String>? = null,
    )

    /** The ten keys, in the order the extension writes them. */
    fun emit(
        snap: Snapshot,
        view: SettingsView,
        carried: Passengers,
        trackedApps: Set<String> = emptySet(),
    ): Map<String, JsonElement> =
        linkedMapOf(
            "rechargePerMin" to JsWire.num(snap.rechargePerMin),
            "capacity" to JsWire.num(snap.capacity),
            "warnSeconds" to JsWire.num(snap.warnSeconds),
            "enabledSites" to enabledSites(view.siteModes, carried.siteModes),
            "customSites" to carried.customSites,
            "hourRules" to hourRules(view.hourRules),
            "hideYtSidebar" to carried.hideYtSidebar,
            "showTimeLeft" to JsonPrimitive(snap.showTimeLeft),
            "frictionCount" to JsWire.num(snap.frictionCount),
            "trackedApps" to JsonArray(trackedApps.map { JsonPrimitive(it) }),
        )

    fun ingest(
        values: Map<String, JsonElement>,
        localModes: Map<String, SiteMode>,
        carried: Passengers,
    ): Arrived {
        val enabled = values["enabledSites"] as? JsonObject
        val patch = SettingsPatch(
            rechargePerMin = values["rechargePerMin"]?.num(),
            capacity = values["capacity"]?.num(),
            warnSeconds = values["warnSeconds"]?.num()?.roundToInt(),
            siteModes = enabled?.let { ownModes(it, localModes) },
            hourRules = (values["hourRules"] as? JsonArray)?.map { it.toRawRule() },
            showTimeLeft = values["showTimeLeft"]?.bool(),
            frictionCount = values["frictionCount"]?.num()?.roundToInt(),
            // customSites and hideYtSidebar are the browser's business. Applying them here
            // would only mean writing them back out in this platform's spelling.
        )
        return Arrived(
            patch = patch,
            passengers = Passengers(
                customSites = values["customSites"]?.takeIf { it is JsonArray } ?: carried.customSites,
                hideYtSidebar = values["hideYtSidebar"] ?: carried.hideYtSidebar,
                siteModes = enabled?.let { foreignModes(it) } ?: carried.siteModes,
            ),
            trackedApps = (values["trackedApps"] as? JsonArray)?.let { arr ->
                arr.mapNotNullTo(LinkedHashSet()) { it.str()?.takeIf { s -> s.isNotEmpty() } }
            },
        )
    }

    // --- enabledSites ---------------------------------------------------------------

    /** Built-ins and app ids are this device's to speak for. */
    private fun owns(id: String): Boolean =
        AppCatalog.isBuiltIn(id) || id.startsWith(AppCatalog.CUSTOM_PREFIX)

    private fun enabledSites(
        own: Map<String, SiteMode>,
        carried: Map<String, JsonElement>,
    ): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for ((id, mode) in own) out[id] = wire(mode)
        for ((id, value) in carried) if (id !in out) out[id] = value
        return JsonObject(out)
    }

    /**
     * The modes this device holds: its own picks, overwritten by every id the wire named
     * that this platform speaks for. A local id the wire does not name is kept, not
     * dropped: nothing deletes a mode on purpose (removing a custom site sets it false),
     * so absence means the id never synced or an unpatched extension pruned it, and the
     * next push restores it either way.
     */
    private fun ownModes(
        arrived: JsonObject,
        local: Map<String, SiteMode>,
    ): Map<String, SiteMode> {
        val out = LinkedHashMap<String, SiteMode>()
        for ((id, mode) in local) out[id] = mode
        for ((id, value) in arrived) if (owns(id)) out[id] = SiteMode.fromWire(value.wire())
        return out
    }

    /** Everything that arrived which this platform does not speak for, held for the next push. */
    private fun foreignModes(arrived: JsonObject): Map<String, JsonElement> {
        val out = LinkedHashMap<String, JsonElement>()
        for ((id, value) in arrived) {
            if (owns(id)) continue
            out[id] = value
        }
        return out
    }

    private fun wire(mode: SiteMode): JsonElement = when (mode) {
        SiteMode.ON -> JsonPrimitive(true)
        SiteMode.OFF -> JsonPrimitive(false)
        SiteMode.BLOCK -> JsonPrimitive("block")
    }

    // --- hourRules -------------------------------------------------------------------

    /**
     * The stored rule shape, field for field. `from` and `to` go out exactly as they came
     * in, never normalized, so a round trip through this phone leaves "9:00" as "9:00".
     */
    private fun hourRules(rules: List<HourRule>): JsonArray = JsonArray(
        rules.map { r ->
            val o = LinkedHashMap<String, JsonElement>()
            o["id"] = JsonPrimitive(r.id)
            o["from"] = JsonPrimitive(r.from)
            o["to"] = JsonPrimitive(r.to)
            o["action"] = JsonPrimitive(r.action.wire)
            when (r.action) {
                RuleAction.BLOCK, RuleAction.OFF -> {
                    o["scope"] = JsonPrimitive(r.scope.wire)
                    o["sites"] = JsonArray(r.sites.map { JsonPrimitive(it) })
                }
                RuleAction.RECHARGE -> o["percent"] = JsWire.num(r.percent)
                RuleAction.CAPACITY -> o["minutes"] = JsWire.num(r.minutes)
            }
            JsonObject(o)
        }
    )

    private fun JsonElement.toRawRule(): RawHourRule? {
        val o = this as? JsonObject ?: return null
        return RawHourRule(
            id = o["id"]?.str(),
            from = o["from"]?.str(),
            to = o["to"]?.str(),
            action = o["action"]?.str(),
            scope = o["scope"]?.str(),
            sites = (o["sites"] as? JsonArray)?.map { it.str() },
            percent = o["percent"]?.num(),
            minutes = o["minutes"]?.num(),
        )
    }

    // --- reading JSON the way the extension writes it ---------------------------------

    private fun JsonElement.num(): Double? =
        (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()

    private fun JsonElement.str(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonElement.bool(): Boolean? =
        (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()

    /** The raw value behind a site mode: true, false, or the string "block". */
    private fun JsonElement.wire(): Any? {
        val p = this as? JsonPrimitive ?: return null
        return if (p.isString) p.content else p.content.toBooleanStrictOrNull()
    }
}
