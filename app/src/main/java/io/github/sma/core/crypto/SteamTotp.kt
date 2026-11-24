package io.github.sma.core.crypto

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object SteamTotp {
    const val ALPHABET = "23456789BCDFGHJKMNPQRTVWXY"

    private const val PERIOD = 30L
    private const val CODE_LENGTH = 5

    /** Returns a 5-character Steam Guard code for the given shared secret and Unix time (seconds). */
    fun code(sharedSecret: ByteArray, unixSeconds: Long): String {
        val counter = Math.floorDiv(unixSeconds, PERIOD)
        val msg = ByteBuffer.allocate(8).putLong(counter).array()

        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(sharedSecret, "HmacSHA1"))
        val h = mac.doFinal(msg)

        val offset = h[h.size - 1].toInt() and 0x0f
        var v = ((h[offset].toInt() and 0xff) shl 24) or
            ((h[offset + 1].toInt() and 0xff) shl 16) or
            ((h[offset + 2].toInt() and 0xff) shl 8) or
            (h[offset + 3].toInt() and 0xff)
        v = v and 0x7fffffff

        val sb = StringBuilder(CODE_LENGTH)
        repeat(CODE_LENGTH) {
            sb.append(ALPHABET[v % ALPHABET.length])
            v /= ALPHABET.length
        }
        return sb.toString()
    }

    /** Seconds until the current code changes. */
    fun secondsRemaining(unixSeconds: Long): Int =
        (PERIOD - Math.floorMod(unixSeconds, PERIOD)).toInt()
}
