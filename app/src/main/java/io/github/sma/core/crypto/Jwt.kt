package io.github.sma.core.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Base64

object Jwt {
    data class Claims(val sub: String?, val aud: List<String>, val exp: Long?)

    /** Parses the payload (second segment) of a JWT. Returns null if it cannot be decoded. */
    fun parse(token: String): Claims? {
        val parts = token.split('.')
        if (parts.size < 2) return null

        val payload = try {
            String(Base64.getUrlDecoder().decode(parts[1].trimEnd('=')), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val element = try {
            Json.parseToJsonElement(payload)
        } catch (e: Exception) {
            return null
        }
        val obj = element as? JsonObject ?: return null

        val sub = (obj["sub"] as? JsonPrimitive)?.contentOrNull
        val aud = when (val a = obj["aud"]) {
            is JsonArray -> a.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(a.contentOrNull)
            else -> emptyList()
        }
        val exp = (obj["exp"] as? JsonPrimitive)?.longOrNull

        return Claims(sub, aud, exp)
    }

    /** True if the token carries the "mobile" audience and has not expired at [nowSeconds]. */
    fun isUsableMobile(token: String, nowSeconds: Long): Boolean {
        val claims = parse(token) ?: return false
        val exp = claims.exp ?: return false
        return "mobile" in claims.aud && exp > nowSeconds
    }

    /** Seconds until the token expires, or null if it has no exp claim. Never negative. */
    fun secondsLeft(token: String, nowSeconds: Long): Long? {
        val exp = parse(token)?.exp ?: return null
        return (exp - nowSeconds).coerceAtLeast(0L)
    }
}
