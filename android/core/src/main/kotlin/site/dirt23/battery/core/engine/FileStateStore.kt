package site.dirt23.battery.core.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import site.dirt23.battery.core.model.BatteryState
import site.dirt23.battery.core.model.CustomEntry
import site.dirt23.battery.core.model.RawHourRule
import site.dirt23.battery.core.model.RuleAction
import site.dirt23.battery.core.model.SiteMode
import site.dirt23.battery.core.rules.Rules
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The state file: one JSON object written whole, replaced atomically. Android can kill the
 * process at any moment, so the new state goes to a temp file, gets fsynced, and only then
 * is renamed into place. A kill leaves either the old state or the new one.
 *
 * Keys match the extension's storage keys exactly, so a state file and a synced settings
 * blob use the same names.
 */
class FileStateStore(private val dir: File) : StateStore {
    private val file = File(dir, FILE_NAME)
    private val tmp = File(dir, "$FILE_NAME.tmp")

    override fun load(): BatteryState? {
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        return StateJson.decode(text)
    }

    override fun save(s: BatteryState) {
        dir.mkdirs()
        val bytes = StateJson.encode(s).toByteArray(Charsets.UTF_8)
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

    private companion object {
        const val FILE_NAME = "state.json"
    }
}

/**
 * The state file codec. Reads are field by field and forgiving, like the extension's
 * `{...DEFAULTS, ...stored}` overlay: a key that is missing, null, or the wrong type falls
 * back to its default rather than failing the whole file. Only text that is not JSON at all
 * is a total loss, and the engine boots the defaults for that.
 */
internal object StateJson {
    fun encode(s: BatteryState): String = buildJsonObject {
        put("charge", finite(s.charge))
        put("capacity", finite(s.capacity))
        put("rechargePerMin", finite(s.rechargePerMin))
        put("warnSeconds", s.warnSeconds)
        putJsonObject("enabledSites") {
            for ((id, mode) in s.siteModes) {
                when (mode) {
                    SiteMode.ON -> put(id, true)
                    SiteMode.OFF -> put(id, false)
                    SiteMode.BLOCK -> put(id, "block")
                }
            }
        }
        putJsonArray("customSites") {
            for (c in s.customEntries) {
                addJsonObject {
                    put("id", c.id)
                    put("name", c.name)
                    put("host", c.host)
                }
            }
        }
        putJsonArray("hourRules") {
            for (r in s.hourRules) {
                addJsonObject {
                    put("id", r.id)
                    // Verbatim, as on the way in: "9:00" stays "9:00" across a round trip.
                    put("from", r.from)
                    put("to", r.to)
                    put("action", r.action.wire)
                    when (r.action) {
                        RuleAction.BLOCK, RuleAction.OFF -> {
                            put("scope", r.scope.wire)
                            putJsonArray("sites") { for (site in r.sites) add(site) }
                        }
                        RuleAction.RECHARGE -> put("percent", r.percent)
                        // Only a capacity rule carries minutes. An explicit null would read
                        // back as a broken cap rather than as an absent key, which the
                        // validator treats differently.
                        RuleAction.CAPACITY -> put("minutes", r.minutes)
                    }
                }
            }
        }
        putJsonObject("sitePasses") { for ((id, expiry) in s.sitePasses) put(id, expiry) }
        put("hideYtSidebar", s.hideYtSidebar)
        put("showTimeLeft", s.showTimeLeft)
        put("depleted", s.depleted)
        put("depletedAt", s.depletedAt)
        put("depletionSeq", s.depletionSeq)
        put("lastTickTs", s.lastTickTs)
        put("remoteDraining", s.remoteDraining)
        put("remoteDrainingTs", s.remoteDrainingTs)
        put("deviceId", s.deviceId)
        put("syncCode", s.syncCode)
        put("serverUrl", s.serverUrl)
        put("serverOffset", s.serverOffset)
        putJsonObject("settingsMeta") { for ((key, ts) in s.settingsMeta) put(key, ts) }
    }.toString()

    fun decode(text: String): BatteryState? {
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val d = BatteryState()
        return BatteryState(
            charge = root.double("charge") ?: d.charge,
            capacity = root.double("capacity") ?: d.capacity,
            rechargePerMin = root.double("rechargePerMin") ?: d.rechargePerMin,
            warnSeconds = root.int("warnSeconds") ?: d.warnSeconds,
            siteModes = root.modeMap("enabledSites"),
            customEntries = root.arr("customSites").mapNotNull { it.toCustomEntry() }.toMutableList(),
            hourRules = Rules.sanitizeRules(root.arr("hourRules").map { it.toRawRule() }),
            sitePasses = root.longMap("sitePasses"),
            hideYtSidebar = root.bool("hideYtSidebar") ?: d.hideYtSidebar,
            showTimeLeft = root.bool("showTimeLeft") ?: d.showTimeLeft,
            depleted = root.bool("depleted") ?: d.depleted,
            depletedAt = root.long("depletedAt"),
            depletionSeq = root.int("depletionSeq") ?: d.depletionSeq,
            lastTickTs = root.long("lastTickTs") ?: d.lastTickTs,
            remoteDraining = root.bool("remoteDraining") ?: d.remoteDraining,
            remoteDrainingTs = root.long("remoteDrainingTs") ?: d.remoteDrainingTs,
            deviceId = root.str("deviceId"),
            syncCode = root.str("syncCode"),
            serverUrl = root.str("serverUrl"),
            serverOffset = root.long("serverOffset") ?: d.serverOffset,
            settingsMeta = root.longMap("settingsMeta"),
        )
    }

    /** NaN and the infinities have no JSON spelling: write 0 and let load clamp it. */
    private fun finite(v: Double): Double = if (v.isFinite()) v else 0.0

    private fun JsonObject.prim(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

    private fun JsonObject.obj(key: String): Map<String, JsonElement> =
        this[key] as? JsonObject ?: emptyMap()

    private fun JsonObject.arr(key: String): List<JsonElement> = this[key] as? JsonArray ?: emptyList()

    private fun JsonObject.double(key: String): Double? = prim(key)?.numberOrNull()

    private fun JsonObject.long(key: String): Long? = prim(key)?.longOrNull()

    private fun JsonObject.int(key: String): Int? = long(key)?.toInt()

    private fun JsonObject.str(key: String): String? = prim(key)?.takeIf { it.isString }?.content

    private fun JsonObject.bool(key: String): Boolean? =
        prim(key)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()

    private fun JsonObject.longMap(key: String): MutableMap<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for ((k, v) in obj(key)) (v as? JsonPrimitive)?.longOrNull()?.let { out[k] = it }
        return out
    }

    private fun JsonObject.modeMap(key: String): MutableMap<String, SiteMode> {
        val out = LinkedHashMap<String, SiteMode>()
        for ((k, v) in obj(key)) out[k] = SiteMode.fromWire(wireValue(v))
        return out
    }

    /** Numbers only: a quoted "junk" is not a number, nor is null. */
    private fun JsonPrimitive.numberOrNull(): Double? = if (isString) null else content.toDoubleOrNull()

    private fun JsonPrimitive.longOrNull(): Long? = numberOrNull()?.takeIf { it.isFinite() }?.toLong()

    /** A site mode as the extension writes it: true, false, or "block". */
    private fun wireValue(v: JsonElement): Any? {
        val p = v as? JsonPrimitive ?: return null
        return if (p.isString) p.content else p.content.toBooleanStrictOrNull()
    }

    private fun JsonElement.toCustomEntry(): CustomEntry? {
        val o = this as? JsonObject ?: return null
        val id = o.str("id") ?: return null
        val host = o.str("host") ?: return null
        return CustomEntry(id = id, name = o.str("name") ?: host, host = host)
    }

    private fun JsonElement.toRawRule(): RawHourRule? {
        val o = this as? JsonObject ?: return null
        return RawHourRule(
            id = o.str("id"),
            from = o.str("from"),
            to = o.str("to"),
            action = o.str("action"),
            scope = o.str("scope"),
            sites = o.arr("sites").map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
            percent = o.double("percent"),
            minutes = o.double("minutes"),
        )
    }
}
