package io.github.sma.core.crypto

import java.util.Base64

object Secrets {
    private val HEX40 = Regex("^[0-9a-fA-F]{40}$")

    /**
     * Decodes a shared/identity secret. A 40-character hex string is decoded as hex;
     * anything else is decoded as Base64 (standard or URL-safe, padded or not).
     * @throws IllegalArgumentException for empty or invalid input.
     */
    fun decode(s: String): ByteArray {
        val t = s.trim()
        require(t.isNotEmpty()) { "Secret is empty" }
        val bytes = if (HEX40.matches(t)) decodeHex(t) else decodeBase64(t)
        require(bytes.isNotEmpty()) { "Secret decodes to empty data" }
        return bytes
    }

    private fun decodeHex(t: String): ByteArray =
        ByteArray(t.length / 2) { i -> t.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun decodeBase64(t: String): ByteArray {
        var body = t.replace('-', '+').replace('_', '/').trimEnd('=')
        val rem = body.length % 4
        if (rem == 1) throw IllegalArgumentException("Invalid Base64 length")
        body += when (rem) {
            2 -> "=="
            3 -> "="
            else -> ""
        }
        return try {
            Base64.getDecoder().decode(body)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid Base64 secret", e)
        }
    }
}
