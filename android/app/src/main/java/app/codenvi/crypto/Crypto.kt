package app.codenvi.crypto

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Kotlin implementation of docs/crypto.md. Must stay byte-compatible with
 * shared/Codenvi.Contracts/Crypto (checked by CryptoVectorTest against the same vectors).
 */
object Base32 {
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun encode(data: ByteArray, bitCount: Int = data.size * 8): String {
        require(bitCount in 0..data.size * 8)
        val sb = StringBuilder((bitCount + 4) / 5)
        var buffer = 0
        var bits = 0
        var consumed = 0
        for (b in data) {
            var i = 7
            while (i >= 0 && consumed < bitCount) {
                buffer = (buffer shl 1) or ((b.toInt() shr i) and 1)
                bits++
                consumed++
                if (bits == 5) {
                    sb.append(ALPHABET[buffer]); buffer = 0; bits = 0
                }
                i--
            }
        }
        if (bits > 0) sb.append(ALPHABET[buffer shl (5 - bits)])
        return sb.toString()
    }

    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (raw in text) {
            if (raw == '-' || raw.isWhitespace()) continue
            val c = when (val u = raw.uppercaseChar()) {
                'I', 'L' -> '1'
                'O' -> '0'
                else -> u
            }
            if (ALPHABET.indexOf(c) < 0) throw IllegalArgumentException("Invalid character '$raw'")
            sb.append(c)
        }
        return sb.toString()
    }

    fun decode(text: String, byteCount: Int): ByteArray {
        val symbols = normalize(text)
        val needed = (byteCount * 8 + 4) / 5
        if (symbols.length != needed) throw IllegalArgumentException("Expected $needed characters, got ${symbols.length}")
        val out = ByteArray(byteCount)
        var buffer = 0
        var bits = 0
        var index = 0
        for (c in symbols) {
            buffer = (buffer shl 5) or ALPHABET.indexOf(c)
            bits += 5
            if (bits >= 8) {
                bits -= 8
                if (index < byteCount) out[index++] = (buffer shr bits).toByte()
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        if (buffer != 0) throw IllegalArgumentException("Non-zero padding bits")
        return out
    }

    fun group(symbols: String, size: Int = 4): String = symbols.chunked(size).joinToString("-")
}

object DeviceKey {
    const val KEY_SIZE = 32
    private const val CHECKSUM_BITS = 20

    fun generate(): ByteArray = ByteArray(KEY_SIZE).also { SecureRandom().nextBytes(it) }

    fun format(key: ByteArray): String {
        require(key.size == KEY_SIZE)
        return Base32.group(Base32.encode(key) + Base32.encode(sha256(key), CHECKSUM_BITS))
    }

    fun formatCompact(key: ByteArray): String = format(key).replace("-", "")

    /** Parses a typed or pasted key; throws IllegalArgumentException with a user-readable message. */
    fun parse(text: String): ByteArray {
        val symbols = Base32.normalize(text)
        if (symbols.length != 56) throw IllegalArgumentException("The key must have 56 characters (got ${symbols.length}).")
        val key = Base32.decode(symbols.substring(0, 52), KEY_SIZE)
        val expected = Base32.encode(sha256(key), CHECKSUM_BITS)
        if (!MessageDigest.isEqual(expected.toByteArray(), symbols.substring(52).toByteArray()))
            throw IllegalArgumentException("Checksum mismatch - check the key for typos.")
        return key
    }

    internal fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
}

object ObjectTypes {
    const val MESSAGE = "message"
    const val PROGRESS = "progress"
    const val PROJECT_META = "project.meta"
    const val SESSION_META = "session.meta"
    const val COMMIT_META = "commit.meta"
    const val CONTROL = "control"
    const val CONTROL_RESPONSE = "control.response"
    const val JOB_ERROR = "job.error"
}

object Directions {
    const val P2D = "p2d"
    const val D2P = "d2p"
    const val META = "meta"
}

data class Aad(val deviceId: String, val objectType: String, val objectId: String, val direction: String, val createdAtMs: Long) {
    fun bytes(): ByteArray =
        "v1|${deviceId.lowercase()}|$objectType|${objectId.lowercase()}|$direction|$createdAtMs".toByteArray(Charsets.UTF_8)

    companion object {
        fun meta(deviceId: String, objectType: String, objectId: String) = Aad(deviceId, objectType, objectId, Directions.META, 0)
    }
}

/** AES-256-GCM with HKDF-derived key; wire = 0x01 | keyVersion(2, BE) | nonce(12) | ciphertext | tag(16). */
object E2E {
    const val WIRE_VERSION: Byte = 1
    const val NONCE_SIZE = 12
    const val TAG_SIZE = 16
    const val HEADER_SIZE = 1 + 2 + NONCE_SIZE
    private val random = SecureRandom()

    fun keyCheck(key: ByteArray): ByteArray = hmac(key, "codenvi-kcv-v1".toByteArray()).copyOf(8)

    fun deriveEncKey(key: ByteArray, deviceId: String): ByteArray =
        hkdf(key, deviceId.lowercase().toByteArray(Charsets.UTF_8), "codenvi-v1-enc".toByteArray(), 32)

    fun encrypt(key: ByteArray, keyVersion: Int, aad: Aad, plaintext: ByteArray,
                nonce: ByteArray = ByteArray(NONCE_SIZE).also { random.nextBytes(it) }): ByteArray {
        require(keyVersion in 1..0xFFFF)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(deriveEncKey(key, aad.deviceId), "AES"), GCMParameterSpec(TAG_SIZE * 8, nonce))
        cipher.updateAAD(aad.bytes())
        val ctAndTag = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(HEADER_SIZE + ctAndTag.size)
            .put(WIRE_VERSION).putShort(keyVersion.toShort()).put(nonce).put(ctAndTag).array()
    }

    fun peekKeyVersion(wire: ByteArray): Int {
        if (wire.size < HEADER_SIZE + TAG_SIZE || wire[0] != WIRE_VERSION) throw GeneralSecurityException("Bad ciphertext header")
        return ((wire[1].toInt() and 0xFF) shl 8) or (wire[2].toInt() and 0xFF)
    }

    /** Throws GeneralSecurityException (incl. AEADBadTagException) if anything does not authenticate. */
    fun decrypt(key: ByteArray, aad: Aad, wire: ByteArray): ByteArray {
        peekKeyVersion(wire)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(deriveEncKey(key, aad.deviceId), "AES"),
            GCMParameterSpec(TAG_SIZE * 8, wire, 3, NONCE_SIZE))
        cipher.updateAAD(aad.bytes())
        return cipher.doFinal(wire, HEADER_SIZE, wire.size - HEADER_SIZE)
    }

    internal fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    /** RFC 5869 HKDF-SHA256. */
    internal fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            previous = hmac(prk, previous + info + byteArrayOf(counter.toByte()))
            val n = minOf(previous.size, length - offset)
            System.arraycopy(previous, 0, out, offset, n)
            offset += n
            counter++
        }
        return out
    }
}
