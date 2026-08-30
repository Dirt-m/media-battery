package site.dirt23.battery.core.sync

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// The sync code and its crypto, ported byte for byte from the extension's sync.js. All of it
// is wire format and frozen: the Crockford alphabet, the HKDF salt and info strings, the 12
// byte IV in front of the ciphertext, the 128 bit GCM tag. The golden vectors in
// core/src/test/resources/interop pin each one against real JS output.
//
// HKDF is RFC 5869 over javax.crypto.Mac; the JDK has no HKDF of its own before 24.
object SyncCrypto {
    /** Crockford base32, no I L O U. */
    private const val B32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** 16 random bytes, 26 characters to type. */
    const val CODE_BYTES = 16

    /** Frozen wire constant under the project's old working name. */
    private val SALT = "social-battery-sync-v1".toByteArray(Charsets.UTF_8)

    private const val INFO_ROUTING = "sb-routing-v1"
    private const val INFO_AUTH = "sb-auth-v1"
    private const val INFO_ENC = "sb-enc-v1"

    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private val random = SecureRandom()

    /** What one code derives to: a public routing id, a bearer token, and the AES key. */
    data class Keys(val routingId: String, val authToken: String, val aesKey: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Keys && routingId == other.routingId && authToken == other.authToken &&
                aesKey.contentEquals(other.aesKey)

        override fun hashCode(): Int =
            (routingId.hashCode() * 31 + authToken.hashCode()) * 31 + aesKey.contentHashCode()
    }

    // --- code handling ----------------------------------------------------------

    // MSB first through a bit buffer. The trailing 3 bits of 16 bytes become a 26th character
    // padded with zeros on the right; decoding drops the 2 spare bits.
    fun base32Encode(bytes: ByteArray): String {
        var bits = 0
        var value = 0
        val out = StringBuilder()
        for (b in bytes) {
            value = (value shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                out.append(B32[(value ushr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) out.append(B32[(value shl (5 - bits)) and 31])
        return out.toString()
    }

    // Accept whatever the user pastes: any spacing, lower case, and the ambiguous
    // characters people substitute (O for 0, I or L for 1).
    fun base32Decode(str: String): ByteArray {
        var bits = 0
        var value = 0
        val out = ArrayList<Byte>()
        for (raw in str.uppercase()) {
            val c = when (raw) {
                'O' -> '0'
                'I', 'L' -> '1'
                else -> raw
            }
            val idx = B32.indexOf(c)
            if (idx < 0) continue
            value = (value shl 5) or idx
            bits += 5
            while (bits >= 8) {
                out.add(((value ushr (bits - 8)) and 255).toByte())
                bits -= 8
            }
        }
        return out.toByteArray()
    }

    /** Group into fours for display. The stored code is the ungrouped upper case string. */
    fun formatCode(code: String): String =
        if (code.isEmpty()) code else code.chunked(4).joinToString(" ")

    // --- derivation -------------------------------------------------------------

    fun deriveKeys(code: String): Keys {
        val raw = base32Decode(code)
        require(raw.size == CODE_BYTES) { "bad code" }
        val prk = hkdfExtract(SALT, raw)
        return Keys(
            routingId = toHex(hkdfExpand(prk, INFO_ROUTING.toByteArray(Charsets.UTF_8), 16)),
            authToken = toB64Url(hkdfExpand(prk, INFO_AUTH.toByteArray(Charsets.UTF_8), 32)),
            aesKey = hkdfExpand(prk, INFO_ENC.toByteArray(Charsets.UTF_8), 32)
        )
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // SecretKeySpec rejects an all zero key where HKDF allows one. Cannot happen here:
        // the salt is a constant and keys come from a real code.
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray = hmac(salt, ikm)

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        var prev = ByteArray(0)
        var done = 0
        var counter = 1
        while (done < length) {
            val block = hmac(prk, prev + info + byteArrayOf(counter.toByte()))
            val take = minOf(block.size, length - done)
            block.copyInto(out, done, 0, take)
            done += take
            prev = block
            counter++
        }
        return out
    }

    // --- documents --------------------------------------------------------------

    /**
     * Takes the already serialized JSON, so key order and number formatting stay with
     * whoever built the document.
     */
    fun encryptDoc(aesKey: ByteArray, doc: String, json: String): ByteArray {
        val iv = ByteArray(IV_BYTES)
        random.nextBytes(iv)
        return encryptDoc(aesKey, doc, json, iv)
    }

    /** Same, with the IV supplied. Only tests pin an IV; live writes must use a fresh one. */
    fun encryptDoc(aesKey: ByteArray, doc: String, json: String, iv: ByteArray): ByteArray {
        require(iv.size == IV_BYTES) { "iv must be $IV_BYTES bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(aesKey, "AES"),
            GCMParameterSpec(TAG_BITS, iv)
        )
        cipher.updateAAD(doc.toByteArray(Charsets.UTF_8))
        val ct = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        return iv + ct
    }

    /** Null on anything that does not authenticate: never applied to local state. */
    fun decryptDoc(aesKey: ByteArray, doc: String, blob: ByteArray): String? {
        if (blob.size < IV_BYTES) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(aesKey, "AES"),
                GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES)
            )
            cipher.updateAAD(doc.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    // --- small helpers ----------------------------------------------------------

    fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append("%02x".format(b.toInt() and 0xFF))
        return sb.toString()
    }

    fun fromHex(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun toB64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
