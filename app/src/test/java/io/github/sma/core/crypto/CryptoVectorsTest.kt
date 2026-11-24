package io.github.sma.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class CryptoVectorsTest {

    private val sharedSecret: ByteArray =
        Base64.getDecoder().decode("AQIDBAUGBwgJCgsMDQ4PEBESExQ=")

    private val identitySecret: ByteArray =
        Base64.getDecoder().decode("ZWZnaGlqa2xtbm9wcXJzdHV2d3g=")

    private val steamId = 76561198000000000L

    @Test
    fun totpVectors() {
        assertEquals("6KR59", SteamTotp.code(sharedSecret, 0L))
        assertEquals("3M9KK", SteamTotp.code(sharedSecret, 1700000000L))
        assertEquals("8BXYN", SteamTotp.code(sharedSecret, 1700000029L))
        assertEquals("8BXYN", SteamTotp.code(sharedSecret, 1700000030L))
        assertEquals("PMG8G", SteamTotp.code(sharedSecret, 2000000000L))
    }

    @Test
    fun secondsRemainingMatchesFormula() {
        val t = 1700000029L
        assertEquals(11, SteamTotp.secondsRemaining(t)) // 1700000029 mod 30 = 19
        assertEquals(30, SteamTotp.secondsRemaining(0L))
    }

    @Test
    fun confirmationKeyVectors() {
        val t = 1700000000L
        assertEquals("gOWv4bG2i11fr44XTjkKTVqKJE0=", ConfirmationKey.generate(identitySecret, t, "list"))
        assertEquals("Q/B/FGJtImU/lZkem00tc5QGqjI=", ConfirmationKey.generate(identitySecret, t, "accept"))
        assertEquals("B5/BXUz89q2ty/a2RTjgPDJgrms=", ConfirmationKey.generate(identitySecret, t, "reject"))
    }

    @Test
    fun deviceIdVector() {
        assertEquals(
            "android:5c9df5a2-d7de-1e2c-8fc8-766523ca130f",
            ConfirmationKey.deviceId("76561198000000000")
        )
    }

    @Test
    fun qrSignatureVectors() {
        val sig1 = QrApproval.signature(sharedSecret, 1, 1234567890123456789L, steamId)
        assertEquals(
            "53f9ca7fc33644474733db0507b6c4c01b14df07fb370bf517337295d4d31384",
            sig1.toHex()
        )

        val link = QrApproval.parse("https://s.team/q/1/18446744073709551615")
        assertTrue(link != null)
        assertEquals(1, link!!.version)
        assertEquals(-1L, link.clientId)
        val sig2 = QrApproval.signature(sharedSecret, link.version, link.clientId, steamId)
        assertEquals(
            "4fbb0f137b9d14c26b67663a0d4003127e785d4f85cf52c5fb68ac653e162941",
            sig2.toHex()
        )
    }

    @Test
    fun qrParseRejectsForeignHost() {
        assertNull(QrApproval.parse("https://example.com/q/1/2"))
    }

    @Test
    fun base32Vector() {
        assertEquals("AEBAGBAFAYDQQCIKBMGA2DQPCAIREEYU", Base32.encode(ByteArray(20) { (it + 1).toByte() }))
    }

    @Test
    fun secretsHexDecode() {
        val decoded = Secrets.decode("0102030405060708090a0b0c0d0e0f1011121314")
        assertTrue(decoded.contentEquals(ByteArray(20) { (it + 1).toByte() }))
    }

    @Test
    fun jwtMobileUsability() {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"sub":"76561198000000000","aud":["web","mobile"],"exp":2000000000}""".toByteArray(Charsets.UTF_8)
        )
        val token = "e30.$payload.x"

        assertTrue(Jwt.isUsableMobile(token, 1900000000L))
        assertFalse(Jwt.isUsableMobile(token, 2000000001L))
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
