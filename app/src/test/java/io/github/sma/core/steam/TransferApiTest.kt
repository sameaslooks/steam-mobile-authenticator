package io.github.sma.core.steam

import io.github.sma.core.proto.ProtoMessage
import io.github.sma.core.proto.ProtoWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TransferApiTest {

    private fun tokenBytes(from: Int, count: Int): ByteArray =
        ByteArray(count) { (from + it).toByte() }

    private fun replacementToken(): ByteArray = ProtoWriter()
        .bytes(1, tokenBytes(1, 20))
        .fixed64(2, -1L)
        .string(3, "R12345")
        .string(6, "test")
        .bytes(8, tokenBytes(101, 20))
        .fixed64(12, 76561198000000000L)
        .toByteArray()

    private fun response(success: Boolean, token: ByteArray?): ProtoMessage {
        val writer = ProtoWriter().bool(1, success)
        if (token != null) writer.message(2, token)
        return ProtoMessage.parse(writer.toByteArray())
    }

    @Test
    fun parsesReplacementToken() {
        val draft = TransferApi.parseReplacement(response(true, replacementToken()))
        assertEquals("18446744073709551615", draft.serialNumber)
        assertEquals("76561198000000000", draft.steamId)
        assertEquals("AQIDBAUGBwgJCgsMDQ4PEBESExQ=", draft.sharedSecret)
        assertEquals("R12345", draft.revocationCode)
        assertEquals("test", draft.accountName)
        assertEquals(
            java.util.Base64.getEncoder().encodeToString(tokenBytes(101, 20)),
            draft.identitySecret,
        )
        assertNull(draft.secret1)
    }

    @Test
    fun draftConvertsToAccount() {
        val draft = TransferApi.parseReplacement(response(true, replacementToken()))
        val account = draft.toAccount("refresh")
        assertEquals("76561198000000000", account.steamId)
        assertEquals("refresh", account.refreshToken)
        assertEquals(true, account.fullyEnrolled)
        assertTrue(account.deviceId!!.startsWith("android:"))
        assertNull(account.secret1)
    }

    @Test
    fun unsuccessfulResponseIsNotSent() {
        try {
            TransferApi.parseReplacement(response(false, replacementToken()))
            fail("Expected TransferException")
        } catch (e: TransferException) {
            assertTrue(e.sent)
            assertFalse(e.retryable)
        }
    }

    @Test
    fun missingSharedSecretThrows() {
        val token = ProtoWriter()
            .bytes(8, tokenBytes(101, 20))
            .fixed64(12, 76561198000000000L)
            .toByteArray()
        try {
            TransferApi.parseReplacement(response(true, token))
            fail("Expected TransferException")
        } catch (e: TransferException) {
            assertTrue(e.sent)
        }
    }
}
