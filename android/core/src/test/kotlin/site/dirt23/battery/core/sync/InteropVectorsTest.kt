package site.dirt23.battery.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import site.dirt23.battery.core.model.Anchor
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Cross implementation proof. resources/interop/js-vectors.json is real output from the
// extension's sync.js (regenerate with `node tests/gen-interop-fixtures.js` in the
// extension repo). Same routing id and auth token, same decrypt, same base64 under the
// fixture's IV, same anchor JSON: that pins the base32 bit packing, the HKDF salt and
// infos, the IV in front, the 128 bit tag on the end, and the anchor document.
//
// A failure here means an Android build can't read a profile the extension wrote. Don't
// relax it.
class InteropVectorsTest {
    private val vectors: JsonObject by lazy {
        val text = javaClass.getResourceAsStream("/interop/js-vectors.json")
            ?.bufferedReader()?.readText()
            ?: error("js-vectors.json is missing from the test resources")
        Json.parseToJsonElement(text).jsonObject
    }

    private fun section(name: String): List<JsonObject> =
        vectors.getValue(name).jsonArray.map { it.jsonObject }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

    private val codes get() = section("codes")
    private val blobs get() = section("blobs")
    private val anchors get() = section("anchorDocs")

    @Test
    fun `the fixtures actually loaded`() {
        assertEquals(5, codes.size)
        assertEquals(4, blobs.size)
        assertEquals(3, anchors.size)
    }

    @Test
    fun `codes encode and derive exactly as the extension does`() {
        for (v in codes) {
            val raw = SyncCrypto.fromHex(v.str("codeBytesHex"))
            val code = SyncCrypto.base32Encode(raw)
            assertEquals(v.str("code"), code, "code for ${v.str("codeBytesHex")}")
            assertEquals(v.str("formatted"), SyncCrypto.formatCode(code))
            assertEquals(
                v.str("codeBytesHex"),
                SyncCrypto.toHex(SyncCrypto.base32Decode(code)),
                "decode of ${v.str("code")}"
            )
            val keys = SyncCrypto.deriveKeys(code)
            assertEquals(v.str("routingId"), keys.routingId, "routingId for $code")
            assertEquals(v.str("authToken"), keys.authToken, "authToken for $code")
        }
    }

    @Test
    fun `a sloppily typed fixture code still derives the same keys`() {
        for (v in codes) {
            val code = v.str("code")
            val sloppy = v.str("formatted").lowercase()
                .replace("0", "o").replace("1", "l")
            assertEquals(code, SyncCrypto.base32Encode(SyncCrypto.base32Decode(sloppy)))
        }
    }

    @Test
    fun `every JS blob decrypts to its exact plaintext`() {
        for (v in blobs) {
            val key = SyncCrypto.deriveKeys(v.str("code")).aesKey
            val blob = Base64.getDecoder().decode(v.str("blobB64"))
            assertEquals(
                v.str("plaintext"),
                SyncCrypto.decryptDoc(key, v.str("doc"), blob),
                "decrypt of ${v.str("doc")} under ${v.str("code")}"
            )
        }
    }

    @Test
    fun `re-encrypting under the fixture iv reproduces the JS bytes`() {
        for (v in blobs) {
            val key = SyncCrypto.deriveKeys(v.str("code")).aesKey
            val iv = SyncCrypto.fromHex(v.str("ivHex"))
            val mine = SyncCrypto.encryptDoc(key, v.str("doc"), v.str("plaintext"), iv)
            assertEquals(
                v.str("blobB64"),
                Base64.getEncoder().encodeToString(mine),
                "blob for ${v.str("doc")} under ${v.str("code")}"
            )
        }
    }

    @Test
    fun `the doc name is bound in, so no blob reads as the other doc`() {
        for (v in blobs) {
            val key = SyncCrypto.deriveKeys(v.str("code")).aesKey
            val blob = Base64.getDecoder().decode(v.str("blobB64"))
            val other = if (v.str("doc") == "charge") "settings" else "charge"
            assertTrue(SyncCrypto.decryptDoc(key, other, blob) == null)
        }
    }

    @Test
    fun `anchors encode to the exact bytes the extension writes, and decode back`() {
        for (v in anchors) {
            val a = v.getValue("anchor").jsonObject
            val anchor = Anchor(
                charge = a.getValue("charge").jsonPrimitive.double,
                asOf = a.getValue("asOf").jsonPrimitive.long,
                draining = a.getValue("draining").jsonPrimitive.boolean,
                depleted = a.getValue("depleted").jsonPrimitive.boolean,
                depletedAt = (a.getValue("depletedAt") as? JsonPrimitive)
                    ?.takeIf { it !is JsonNull }?.long,
                writer = (a.getValue("writer") as? JsonPrimitive)
                    ?.takeIf { it !is JsonNull }?.content,
            )
            val json = v.str("json")
            assertEquals(json, AnchorWire.encode(anchor), "encode of ${v.str("name")}")
            assertEquals(anchor, AnchorWire.decode(json), "decode of ${v.str("name")}")
        }
    }
}
