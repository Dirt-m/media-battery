package site.dirt23.battery.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The settings document: how it merges, and how it is written.
//
// The byte level part matters because a document is compared, merged, and re-emitted across
// two implementations, and Kotlin writes an integral double as 1800.0 where JavaScript
// writes 1800. The fixtures in resources/interop are real JSON.stringify output from the
// extension.
class SettingsWireTest {

    private val vectors by lazy {
        val text = javaClass.getResourceAsStream("/interop/js-vectors.json")
            ?.bufferedReader()?.readText()
            ?: error("js-vectors.json is missing from the test resources")
        Json.parseToJsonElement(text).jsonObject
    }

    // --- the merge -------------------------------------------------------------------

    @Test
    fun `settings merge is per key last write wins`() {
        val local = linkedMapOf(
            "capacity" to SettingsEntry(JsWire.num(1800.0), 10),
            "rechargePerMin" to SettingsEntry(JsWire.num(5.0), 30),
        )
        val remote = linkedMapOf(
            "capacity" to SettingsEntry(JsWire.num(3600.0), 20),
            "hideYtSidebar" to SettingsEntry(JsonPrimitive(true), 5),
        )
        val merged = SettingsWire.mergeSettings(local, remote)
        assertEquals("3600", merged.getValue("capacity").value.jsonPrimitive.content) // remote newer
        assertEquals("5", merged.getValue("rechargePerMin").value.jsonPrimitive.content) // local only
        assertEquals(true, merged.getValue("hideYtSidebar").value.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `settings merge ties go to local`() {
        val merged = SettingsWire.mergeSettings(
            mapOf("capacity" to SettingsEntry(JsonPrimitive(1), 10)),
            mapOf("capacity" to SettingsEntry(JsonPrimitive(2), 10)),
        )
        assertEquals("1", merged.getValue("capacity").value.jsonPrimitive.content)
    }

    @Test
    fun `a key only one side has survives the merge`() {
        val merged = SettingsWire.mergeSettings(
            mapOf("a" to SettingsEntry(JsonPrimitive(1), 1)),
            mapOf("b" to SettingsEntry(JsonPrimitive(2), 2)),
        )
        assertEquals(setOf("a", "b"), merged.keys)
    }

    @Test
    fun `sameDoc looks at content, not at key order`() {
        val a = linkedMapOf(
            "one" to SettingsEntry(JsonPrimitive(1), 1),
            "two" to SettingsEntry(JsonPrimitive(2), 2),
        )
        val b = linkedMapOf(
            "two" to SettingsEntry(JsonPrimitive(2), 2),
            "one" to SettingsEntry(JsonPrimitive(1), 1),
        )
        assertTrue(SettingsWire.sameDoc(a, b))
        assertFalse(SettingsWire.sameDoc(a, b + ("three" to SettingsEntry(JsonPrimitive(3), 3))))
        assertFalse(SettingsWire.sameDoc(a, linkedMapOf("one" to SettingsEntry(JsonPrimitive(1), 9), "two" to a.getValue("two"))))
    }

    // --- the participation rule --------------------------------------------------------

    @Test
    fun `the local document carries this device's keys plus every foreign one`() {
        val doc = SettingsWire.localDoc(
            own = linkedMapOf("capacity" to JsWire.num(1800.0)),
            meta = mapOf("capacity" to 7L),
            foreign = mapOf("androidTrackedApps" to SettingsEntry(JsonPrimitive("x"), 42)),
        )
        assertEquals(listOf("capacity", "androidTrackedApps"), doc.keys.toList())
        assertEquals(7L, doc.getValue("capacity").ts)
        assertEquals(42L, doc.getValue("androidTrackedApps").ts)
    }

    @Test
    fun `a key stops being foreign once this device maintains it`() {
        val doc = SettingsWire.localDoc(
            own = linkedMapOf("capacity" to JsWire.num(60.0)),
            meta = mapOf("capacity" to 9L),
            foreign = mapOf("capacity" to SettingsEntry(JsonPrimitive(1800), 42)),
        )
        assertEquals(1, doc.size)
        assertEquals(9L, doc.getValue("capacity").ts)
    }

    @Test
    fun `foreignOf keeps exactly what this device does not maintain`() {
        val merged = linkedMapOf(
            "capacity" to SettingsEntry(JsonPrimitive(1800), 1),
            "androidTrackedApps" to SettingsEntry(JsonPrimitive("x"), 2),
        )
        assertEquals(setOf("androidTrackedApps"), SettingsWire.foreignOf(merged, setOf("capacity")).keys)
    }

    // --- number rendering ---------------------------------------------------------------

    @Test
    fun `every number renders exactly as JSON stringify renders it`() {
        val cases = vectors.getValue("numbers").jsonArray
        assertTrue(cases.size >= 20, "the number fixtures did not load")
        for (c in cases) {
            val o = c.jsonObject
            val bits = o.getValue("bitsHex").jsonPrimitive.content
            val expected = o.getValue("json").jsonPrimitive.content
            val v = java.lang.Double.longBitsToDouble(bits.toULong(16).toLong())
            assertEquals(expected, JsWire.format(v), "rendering of $bits")
        }
    }

    @Test
    fun `what JSON cannot carry becomes null, the way JSON stringify does`() {
        assertEquals(null, JsWire.format(Double.NaN))
        assertEquals(null, JsWire.format(Double.POSITIVE_INFINITY))
        assertEquals("null", Json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), JsWire.num(Double.NaN)))
    }

    // --- the document, byte for byte -----------------------------------------------------

    @Test
    fun `each fixture document encodes to the exact bytes the extension writes`() {
        val docs = vectors.getValue("settingsDocs").jsonArray
        assertTrue(docs.size >= 3, "the settings fixtures did not load")
        for (d in docs) {
            val o = d.jsonObject
            val name = o.getValue("name").jsonPrimitive.content
            val entries = rebuild(Json.parseToJsonElement(o.getValue("entriesJson").jsonPrimitive.content).jsonArray)
            assertEquals(o.getValue("json").jsonPrimitive.content, SettingsWire.encodeDoc(entries), "document $name")
        }
    }

    @Test
    fun `a fixture document survives a decode and re-encode unchanged`() {
        for (d in vectors.getValue("settingsDocs").jsonArray) {
            val o = d.jsonObject
            val text = o.getValue("json").jsonPrimitive.content
            val decoded = SettingsWire.decodeDoc(text) ?: error("failed to decode ${o["name"]}")
            assertEquals(text, SettingsWire.encodeDoc(decoded), "round trip of ${o["name"]}")
        }
    }

    @Test
    fun `a document that is not a settings document decodes to null`() {
        assertEquals(null, SettingsWire.decodeDoc("not json at all"))
        assertEquals(null, SettingsWire.decodeDoc("{\"charge\":1}"))
    }

    /**
     * Rebuild a fixture document the way the app would: numbers and booleans made here in
     * Kotlin, foreign keys parsed and re-emitted untouched.
     */
    private fun rebuild(entries: JsonArray): Map<String, SettingsEntry> {
        val out = LinkedHashMap<String, SettingsEntry>()
        for (e in entries) {
            val o = e.jsonObject
            val raw = o.getValue("valueJson").jsonPrimitive.content
            val value = when (val kind = o.getValue("kind").jsonPrimitive.content) {
                "number" -> JsWire.num(raw.toDouble())
                "boolean" -> JsonPrimitive(raw.toBoolean())
                "verbatim" -> Json.parseToJsonElement(raw)
                else -> error("unknown fixture kind $kind")
            }
            out[o.getValue("key").jsonPrimitive.content] = SettingsEntry(value, o.getValue("ts").jsonPrimitive.long)
        }
        return out
    }
}
