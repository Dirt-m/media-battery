package site.dirt23.battery.core.sync

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.Locale
import kotlin.math.abs

/**
 * One entry in the settings document: the value as it travels, and when the key was last
 * written on some device. Settings merge per key on that timestamp, so two devices editing
 * different settings both keep their edit.
 *
 * The value stays a [JsonElement] because a key this platform does not understand has to
 * survive the round trip byte for byte, which means never decoding it.
 */
data class SettingsEntry(val value: JsonElement, val ts: Long)

/**
 * The settings document: `{"keys": { name: { value, ts } }}`, encrypted like everything else.
 * Two rules make it work across platforms.
 *
 * Last write wins per key, never per document. The two platforms maintain different key sets,
 * and a whole document write would let the smaller vocabulary erase the larger one on every
 * push.
 *
 * A device re-emits every key it does not own, verbatim, with the timestamp it arrived with.
 * That rule lives here rather than in the engine, so one place decides what a device is
 * allowed to forget.
 */
object SettingsWire {

    /** The document name, bound into the ciphertext as additional data. */
    const val DOC = "settings"

    private val json = Json {
        encodeDefaults = true
        prettyPrint = false
    }

    /**
     * Per key last write wins, ported from sync.js mergeSettings (sync.js:119).
     *
     * A key only one side has is kept as it stands, and a tie goes to local: two devices that
     * write the same key in the same millisecond would otherwise flip back and forth forever,
     * each adopting the other.
     */
    fun mergeSettings(
        local: Map<String, SettingsEntry>,
        remote: Map<String, SettingsEntry>,
    ): Map<String, SettingsEntry> {
        val out = LinkedHashMap<String, SettingsEntry>()
        // Local keys first, then remote-only ones: the order sync.js gets from spreading both
        // key sets into a Set.
        for (k in local.keys) out[k] = pick(local[k], remote[k])
        for (k in remote.keys) if (k !in out) out[k] = pick(local[k], remote[k])
        return out
    }

    private fun pick(a: SettingsEntry?, b: SettingsEntry?): SettingsEntry {
        if (b == null) return a!!
        if (a == null) return b
        return if (b.ts > a.ts) b else a
    }

    /**
     * The document this device would publish: its own settings stamped from [meta], plus every
     * foreign key it last received, unchanged. [own] is what the adapter reports and wins over
     * a stored foreign entry of the same name, which is how a key stops being foreign once a
     * version that understands it ships.
     */
    fun localDoc(
        own: Map<String, JsonElement>,
        meta: Map<String, Long>,
        foreign: Map<String, SettingsEntry>,
    ): Map<String, SettingsEntry> {
        val doc = LinkedHashMap<String, SettingsEntry>()
        for ((k, v) in own) doc[k] = SettingsEntry(v, meta[k] ?: 0L)
        for ((k, e) in foreign) if (k !in doc) doc[k] = e
        return doc
    }

    /** The entries of [merged] this device does not maintain, to be held and re-emitted. */
    fun foreignOf(
        merged: Map<String, SettingsEntry>,
        ownKeys: Set<String>,
    ): Map<String, SettingsEntry> {
        val out = LinkedHashMap<String, SettingsEntry>()
        for ((k, e) in merged) if (k !in ownKeys) out[k] = e
        return out
    }

    /**
     * Do two documents say the same thing? sync.js compares serialized JSON (sync.js:320);
     * comparing the maps answers the same question without calling two documents different
     * over key order.
     */
    fun sameDoc(a: Map<String, SettingsEntry>, b: Map<String, SettingsEntry>): Boolean {
        if (a.size != b.size) return false
        for ((k, e) in a) {
            val o = b[k] ?: return false
            if (o.ts != e.ts || o.value != e.value) return false
        }
        return true
    }

    // --- wire -------------------------------------------------------------------

    /**
     * Serialize exactly as the extension does: `{"keys":{...}}`, each entry
     * `{"value":...,"ts":...}` in that order, no whitespace. Values pass through untouched, so
     * a foreign key is re-emitted with the bytes it arrived with.
     */
    fun encodeDoc(keys: Map<String, SettingsEntry>): String {
        val entries = LinkedHashMap<String, JsonElement>()
        for ((k, e) in keys) {
            entries[k] = JsonObject(
                linkedMapOf(
                    "value" to e.value,
                    "ts" to JsWire.num(e.ts),
                )
            )
        }
        return json.encodeToString(
            JsonElement.serializer(),
            JsonObject(linkedMapOf("keys" to JsonObject(entries)))
        )
    }

    /** Null when the plaintext is not a settings document we recognize. */
    fun decodeDoc(text: String): Map<String, SettingsEntry>? = try {
        val keys = Json.parseToJsonElement(text).jsonObject["keys"]?.jsonObject
        keys?.let {
            val out = LinkedHashMap<String, SettingsEntry>()
            for ((k, v) in it) {
                val o = v.jsonObject
                val value = o["value"] ?: JsonNull
                val ts = o["ts"]?.jsonPrimitive?.long ?: 0L
                out[k] = SettingsEntry(value, ts)
            }
            out
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Numbers, rendered the way JSON.stringify renders them. Wire format: Kotlin prints an
 * integral double as `1800.0` where JavaScript prints `1800`, and a settings document that
 * disagreed on that would be a different document every time it crossed platforms. The byte
 * level fixtures in resources/interop pin it against real JS output.
 *
 * The algorithm is ECMA-262's Number::toString: the shortest decimal that reads back as the
 * same double, laid out plainly unless the exponent leaves the range JavaScript writes plainly
 * (below 1e-6, or 1e21 and up).
 */
object JsWire {

    /** A number element that encodes the way JavaScript would write it. */
    @OptIn(ExperimentalSerializationApi::class)
    fun num(v: Double): JsonElement {
        val s = format(v)
        // JSON has no NaN or Infinity, and JSON.stringify writes null for both.
        return if (s == null) JsonNull else JsonUnquotedLiteral(s)
    }

    fun num(v: Long): JsonElement = JsonPrimitive(v)

    fun num(v: Int): JsonElement = JsonPrimitive(v)

    /** The rendering itself, or null for a value JSON cannot carry. */
    fun format(v: Double): String? {
        if (v.isNaN() || v.isInfinite()) return null
        if (v == 0.0) return "0" // and negative zero with it, which JSON.stringify writes as 0
        if (v < 0) return "-" + format(-v)

        // The shortest significand that still reads back as this exact double.
        var digits = ""
        var n = 0
        for (p in 1..17) {
            val s = String.format(Locale.ROOT, "%." + (p - 1) + "e", v)
            if (s.toDouble() != v) continue
            val at = s.indexOf('e')
            digits = s.substring(0, at).replace(".", "").trimEnd('0').ifEmpty { "0" }
            // The significand is read as 0.digits, so the exponent gains one.
            n = s.substring(at + 1).toInt() + 1
            break
        }
        val k = digits.length
        return when {
            n in k..21 -> digits + "0".repeat(n - k)
            n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
            n in -5..0 -> "0." + "0".repeat(-n) + digits
            else -> {
                val mantissa = if (k == 1) digits else digits[0] + "." + digits.substring(1)
                val e = n - 1
                mantissa + "e" + (if (e < 0) "-" else "+") + abs(e)
            }
        }
    }
}
