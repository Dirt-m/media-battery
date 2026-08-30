package site.dirt23.battery.core.sync

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

// The pure crypto tests from the extension's tests/sync.test.js, one for one. Byte level
// agreement between the two sides is InteropVectorsTest.
class SyncCryptoTest {
    private fun bytes(f: (Int) -> Int) = ByteArray(16) { f(it).toByte() }

    @Test
    fun `base32 round trips 16 random bytes`() {
        val b = bytes { (it * 37 + 5) % 256 }
        assertContentEquals(b, SyncCrypto.base32Decode(SyncCrypto.base32Encode(b)))
    }

    @Test
    fun `a code encodes to 26 characters`() {
        assertEquals(26, SyncCrypto.base32Encode(bytes { it }).length)
    }

    @Test
    fun `code entry forgives spacing, case, and ambiguous characters`() {
        val code = SyncCrypto.base32Encode(bytes { it * 11 % 256 })
        val sloppy = SyncCrypto.formatCode(code).lowercase()
            .replace("0", "o").replace("1", "l")
        assertEquals(code, SyncCrypto.base32Encode(SyncCrypto.base32Decode(sloppy)))
    }

    @Test
    fun `keys derive deterministically and differ per purpose`() {
        val code = SyncCrypto.base32Encode(bytes { it })
        val a = SyncCrypto.deriveKeys(code)
        val b = SyncCrypto.deriveKeys(code)
        assertEquals(a.routingId, b.routingId)
        assertEquals(a.authToken, b.authToken)
        assertContentEquals(a.aesKey, b.aesKey)
        assertNotEquals(a.routingId, a.authToken)
        assertEquals(32, a.routingId.length) // 16 bytes of hex
        assertEquals(32, a.aesKey.size)
    }

    @Test
    fun `encrypt decrypt round trips, and a doc name mismatch decrypts to null`() {
        val code = SyncCrypto.base32Encode(bytes { 255 - it })
        val key = SyncCrypto.deriveKeys(code).aesKey
        val anchor = """{"charge":123,"asOf":456,"draining":true,"writer":"ab12"}"""
        val blob = SyncCrypto.encryptDoc(key, "charge", anchor)
        assertEquals(anchor, SyncCrypto.decryptDoc(key, "charge", blob))
        // The doc name is bound in as AAD: a charge blob can't be replayed as settings.
        assertNull(SyncCrypto.decryptDoc(key, "settings", blob))
    }

    @Test
    fun `a tampered blob and a foreign key decrypt to null`() {
        val key = SyncCrypto.deriveKeys(SyncCrypto.base32Encode(bytes { it })).aesKey
        val other = SyncCrypto.deriveKeys(SyncCrypto.base32Encode(bytes { it + 1 })).aesKey
        val blob = SyncCrypto.encryptDoc(key, "charge", """{"charge":1}""")
        assertNull(SyncCrypto.decryptDoc(other, "charge", blob))
        val tampered = blob.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 1).toByte()
        assertNull(SyncCrypto.decryptDoc(key, "charge", tampered))
        assertNull(SyncCrypto.decryptDoc(key, "charge", ByteArray(4)))
    }

    @Test
    fun `every encryption draws a fresh iv`() {
        val key = SyncCrypto.deriveKeys(SyncCrypto.base32Encode(bytes { it })).aesKey
        val a = SyncCrypto.encryptDoc(key, "charge", """{"charge":1}""")
        val b = SyncCrypto.encryptDoc(key, "charge", """{"charge":1}""")
        assertNotEquals(SyncCrypto.toHex(a.copyOf(12)), SyncCrypto.toHex(b.copyOf(12)))
    }

    @Test
    fun `format groups in fours`() {
        assertEquals("ABCD EFGH IJ", SyncCrypto.formatCode("ABCDEFGHIJ"))
    }
}
