package io.github.sma.core.crypto

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object ConfirmationKey {
    private const val MAX_TAG_BYTES = 32

    /** Confirmation key for the given Unix time and tag (e.g. "list", "accept", "reject"). */
    fun generate(identitySecret: ByteArray, unixSeconds: Long, tag: String): String {
        val tagBytes = tag.toByteArray(Charsets.UTF_8)
        val tagPart = tagBytes.copyOf(minOf(tagBytes.size, MAX_TAG_BYTES))
        val msg = ByteBuffer.allocate(8 + tagPart.size)
            .putLong(unixSeconds)
            .put(tagPart)
            .array()

        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(identitySecret, "HmacSHA1"))
        return Base64.getEncoder().encodeToString(mac.doFinal(msg))
    }

    /** Device id in the form "android:xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" derived from the SteamID64. */
    fun deviceId(steamId64: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(steamId64.toByteArray(Charsets.US_ASCII))
        val h = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "android:" +
            h.substring(0, 8) + "-" +
            h.substring(8, 12) + "-" +
            h.substring(12, 16) + "-" +
            h.substring(16, 20) + "-" +
            h.substring(20, 32)
    }
}
