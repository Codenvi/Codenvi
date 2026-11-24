package app.codenvi

import app.codenvi.crypto.Aad
import app.codenvi.crypto.Base32
import app.codenvi.crypto.DeviceKey
import app.codenvi.crypto.Directions
import app.codenvi.crypto.E2E
import app.codenvi.crypto.ObjectTypes
import app.codenvi.data.ConnectionString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.GeneralSecurityException
import java.util.Base64

/** Same vectors as docs/crypto.md and server/tests/Codenvi.Server.Tests/CryptoVectorTests.cs. */
class CryptoVectorTest {
    private val key = ByteArray(32) { it.toByte() }
    private val deviceId = "3f2504e0-4f89-41d3-9a0c-cc5d7e0c3b6a"
    private val messageId = "9b2f6c1e-1a2b-4c3d-8e9f-0a1b2c3d4e5f"
    private val createdAt = 1764000000000L
    private val wireHex = "010001000102030405060708090a0b51028004c212aa8c6e9925fe8ed0dab0b5200523bd0224f6cfc84459c05e00ae"

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun base32Vector() = assertEquals("000G40R40M30E209185GR38E1W", Base32.encode(ByteArray(16) { it.toByte() }))

    @Test fun deviceKeyDisplay() =
        assertEquals("000G-40R4-0M30-E209-185G-R38E-1W81-24GK-2GAH-C5RR-34D1-P70X-3RFG-CC6W", DeviceKey.format(key))

    @Test fun deviceKeyParseAndTypo() {
        assertArrayEquals(key, DeviceKey.parse("000g40r40m30e209185gr38e1w8124gk2gahc5rr34d1p70x3rfgcc6w"))
        try {
            DeviceKey.parse("000G-40R4-0M30-E209-185G-R38E-1W81-24GK-2GAH-C5RR-34D1-P70X-3RFG-CC6X")
            fail("typo not detected")
        } catch (e: IllegalArgumentException) { /* expected */ }
    }

    @Test fun kcv() = assertEquals("868d11c77051b2cb", hex(E2E.keyCheck(key)))

    @Test fun hkdf() = assertEquals("cc52d91b845455535919bafcddced1c60c49aa235b37fc4c3a9fa4ded3d736ec", hex(E2E.deriveEncKey(key, deviceId)))

    @Test fun encryptVector() {
        val aad = Aad(deviceId, ObjectTypes.MESSAGE, messageId, Directions.P2D, createdAt)
        assertEquals("v1|$deviceId|message|$messageId|p2d|$createdAt", String(aad.bytes()))
        val wire = E2E.encrypt(key, 1, aad, """{"text":"hello"}""".toByteArray(), ByteArray(12) { it.toByte() })
        assertEquals(wireHex, hex(wire))
        assertEquals("AQABAAECAwQFBgcICQoLUQKABMISqoxumSX+jtDasLUgBSO9AiT2z8hEWcBeAK4=", Base64.getEncoder().encodeToString(wire))
        assertEquals("""{"text":"hello"}""", String(E2E.decrypt(key, aad, wire)))
        assertEquals(1, E2E.peekKeyVersion(wire))
    }

    @Test fun tamperingFails() {
        val aad = Aad(deviceId, ObjectTypes.MESSAGE, messageId, Directions.P2D, createdAt)
        val wire = E2E.encrypt(key, 1, aad, "hi".toByteArray())
        for (bad in listOf(aad.copy(direction = Directions.D2P), aad.copy(createdAtMs = createdAt + 1), aad.copy(objectId = deviceId))) {
            try { E2E.decrypt(key, bad, wire); fail("moved ciphertext accepted") } catch (e: GeneralSecurityException) { }
        }
    }

    @Test fun connectionStrings() {
        val cs = ConnectionString(host = "h.example:8443", pairingCode = "ABCD", deviceId = deviceId, keyVersion = 2, key = key, deviceName = "Home PC")
        val parsed = ConnectionString.parse(cs.toString())
        assertEquals("h.example:8443", parsed.host)
        assertEquals(deviceId, parsed.deviceId)
        assertEquals(2, parsed.keyVersion)
        assertArrayEquals(key, parsed.key)
        assertEquals("Home PC", parsed.deviceName)
        assertTrue(parsed.carriesKey)
        val keyOnly = ConnectionString.parse(ConnectionString(deviceId = deviceId, keyVersion = 1, key = key).toString())
        assertTrue(keyOnly.isKeyOnly)
    }
}
