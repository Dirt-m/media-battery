package app.mediabattery.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import site.dirt23.battery.core.sync.SettingsEntry
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Settings that travel on the wire but belong to another platform.
 *
 * The settings document merges per key, so a device that pushes a key it does not understand
 * has to push back exactly what it last received, or the smaller vocabulary erases the larger
 * one. Three of the extension's eight keys are like that here: custom sites, the YouTube
 * sidebar switch, and the `enabledSites` entries for sites this phone has never heard of. All
 * three are held as raw JSON and re-emitted untouched.
 */
data class Passengers(
    val customSites: JsonElement = JsonArray(emptyList()),
    val hideYtSidebar: JsonElement = JsonPrimitive(false),
    /** `enabledSites` entries for ids this device does not own, exactly as they arrived. */
    val siteModes: Map<String, JsonElement> = emptyMap(),
)

/** Everything the sync engine keeps between runs. See [SyncStore] for why it is its own file. */
data class SyncRecord(
    val syncCode: String? = null,
    val serverUrl: String? = null,
    val settingsMeta: Map<String, Long> = emptyMap(),
    val foreignSettings: Map<String, SettingsEntry> = emptyMap(),
    val passengers: Passengers = Passengers(),
)

/**
 * The sync engine's own state, in its own file rather than the battery state file: the engine
 * holds that one whole in memory and rewrites it on every save, so a second writer would be
 * overwritten on the next tick. Everything here is written by the sync layer and read by
 * nobody else.
 *
 * The server clock offset is the exception. That is timekeeping, so the engine keeps it and
 * shifts it with every other timestamp when the anchor moves.
 *
 * The write is the same as the state file's: a temporary file, fsynced, then renamed into
 * place. Losing this file loses the sync code, and the code is the profile.
 */
class SyncStore(private val dir: File) {
    private val file = File(dir, FILE_NAME)
    private val tmp = File(dir, "$FILE_NAME.tmp")

    private val _record = MutableStateFlow(load())

    /** The current record. The sync screen renders straight off it. */
    val record: StateFlow<SyncRecord> = _record.asStateFlow()

    /** Edit and write. A change that changes nothing does not touch the disk. */
    @Synchronized
    fun update(edit: (SyncRecord) -> SyncRecord) {
        val next = edit(_record.value)
        if (next == _record.value) return
        _record.value = next
        runCatching { save(next) }
    }

    private fun load(): SyncRecord {
        if (!file.isFile) return SyncRecord()
        val text = runCatching { file.readText() }.getOrNull() ?: return SyncRecord()
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return SyncRecord()
        return SyncRecord(
            syncCode = root.str("syncCode"),
            serverUrl = root.str("serverUrl"),
            settingsMeta = root.obj("settingsMeta").mapNotNull { (k, v) ->
                (v as? JsonPrimitive)?.content?.toLongOrNull()?.let { k to it }
            }.toMap(),
            foreignSettings = root.obj("foreignSettings").mapNotNull { (k, v) ->
                val o = v as? JsonObject ?: return@mapNotNull null
                val ts = (o["ts"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
                k to SettingsEntry(o["value"] ?: JsonNull, ts)
            }.toMap(),
            passengers = (root["passengers"] as? JsonObject).let { p ->
                Passengers(
                    customSites = p?.get("customSites") ?: JsonArray(emptyList()),
                    hideYtSidebar = p?.get("hideYtSidebar") ?: JsonPrimitive(false),
                    siteModes = (p?.get("enabledSites") as? JsonObject)?.toMap() ?: emptyMap(),
                )
            },
        )
    }

    private fun save(r: SyncRecord) {
        dir.mkdirs()
        val root = JsonObject(
            linkedMapOf(
                "syncCode" to (r.syncCode?.let { JsonPrimitive(it) } ?: JsonNull),
                "serverUrl" to (r.serverUrl?.let { JsonPrimitive(it) } ?: JsonNull),
                "settingsMeta" to JsonObject(r.settingsMeta.mapValues { JsonPrimitive(it.value) }),
                "foreignSettings" to JsonObject(
                    r.foreignSettings.mapValues { (_, e) ->
                        JsonObject(linkedMapOf("value" to e.value, "ts" to JsonPrimitive(e.ts)))
                    }
                ),
                "passengers" to JsonObject(
                    linkedMapOf(
                        "customSites" to r.passengers.customSites,
                        "hideYtSidebar" to r.passengers.hideYtSidebar,
                        "enabledSites" to JsonObject(r.passengers.siteModes),
                    )
                ),
            )
        )
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        try {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.obj(key: String): Map<String, JsonElement> =
        this[key] as? JsonObject ?: emptyMap()

    private companion object {
        const val FILE_NAME = "sync.json"
    }
}
