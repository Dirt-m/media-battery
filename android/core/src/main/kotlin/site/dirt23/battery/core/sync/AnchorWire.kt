package site.dirt23.battery.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import site.dirt23.battery.core.model.Anchor

/**
 * The charge document: what the charge was, when that was true, whether it was falling.
 *
 * Key order and number rendering follow the extension's getAnchor (background.js:750), so both
 * sides write byte identical documents. Unknown fields are ignored on decode, so either side
 * can grow the anchor.
 */
object AnchorWire {

    /** The document name, bound into the ciphertext as additional data. */
    const val DOC = "charge"

    fun encode(a: Anchor): String {
        val o = linkedMapOf<String, JsonElement>(
            "charge" to JsWire.num(a.charge),
            "asOf" to JsWire.num(a.asOf),
            "draining" to JsonPrimitive(a.draining),
            "depleted" to JsonPrimitive(a.depleted),
            "depletedAt" to (a.depletedAt?.let { JsWire.num(it) } ?: JsonNull),
            "writer" to (a.writer?.let { JsonPrimitive(it) } ?: JsonNull),
        )
        return Json.encodeToString(JsonElement.serializer(), JsonObject(o))
    }

    /** Null on anything that is not an anchor: never applied to local state. */
    fun decode(text: String): Anchor? = try {
        val o = Json.parseToJsonElement(text).jsonObject
        // Same bar as the extension (background.js:668): a non numeric charge or asOf is not
        // an anchor. Defaulting a missing asOf to 0 projects from the epoch, which lower-wins
        // absorbs but which pushes an overwrite the extension would never make.
        val charge = o["charge"]?.numOrNull()
        val asOf = o["asOf"]?.numOrNull()
        if (charge == null || asOf == null) {
            null
        } else {
            Anchor(
                charge = charge,
                asOf = asOf.toLong(),
                draining = o["draining"]?.boolOrFalse() ?: false,
                depleted = o["depleted"]?.boolOrFalse() ?: false,
                depletedAt = o["depletedAt"]?.longOrZero(),
                writer = (o["writer"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            )
        }
    } catch (_: Exception) {
        null
    }

    /** JSON numbers only: a numeric string does not count. */
    private fun JsonElement.numOrNull(): Double? =
        (this as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.content?.toDoubleOrNull()

    private fun JsonElement.longOrZero(): Long? =
        if (this is JsonNull) null else runCatching { jsonPrimitive.long }.getOrNull()

    private fun JsonElement.boolOrFalse(): Boolean =
        runCatching { jsonPrimitive.boolean }.getOrDefault(false)
}
