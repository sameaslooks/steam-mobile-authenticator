package io.github.sma.core.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object QrApproval {
    data class Link(val version: Int, val clientId: Long)

    private val LINK_REGEX = Regex("""^https?://s\.team/q/(\d+)/(\d+)(\?.*)?$""")

    /** Parses an s.team/q/{version}/{clientId} link. Returns null if the text is not such a link. */
    fun parse(text: String): Link? {
        val match = LINK_REGEX.matchEntire(text.trim()) ?: return null
        val version = match.groupValues[1].toIntOrNull() ?: return null
        val clientId = match.groupValues[2].toULongOrNull()?.toLong() ?: return null
        return Link(version, clientId)
    }

    /**
     * HMAC-SHA256 over 18 little-endian bytes: uint16 version, uint64 clientId, uint64 steamId64.
     */
    fun signature(sharedSecret: ByteArray, version: Int, clientId: Long, steamId64: Long): ByteArray {
        val msg = ByteBuffer.allocate(18)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(version.toShort())
            .putLong(clientId)
            .putLong(steamId64)
            .array()

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(sharedSecret, "HmacSHA256"))
        return mac.doFinal(msg)
    }
}
