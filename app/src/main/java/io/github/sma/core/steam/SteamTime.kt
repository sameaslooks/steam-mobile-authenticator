package io.github.sma.core.steam

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.FormBody
import okhttp3.Request
import java.io.IOException

object SteamTime {
    @Volatile
    var offsetSeconds: Long = 0L

    @Volatile
    var lastSyncMillis: Long = 0L

    @Volatile
    var lastError: String? = null

    private const val STALE_AFTER_MILLIS = 15 * 60 * 1000L

    fun now(): Long = System.currentTimeMillis() / 1000 + offsetSeconds

    fun isStale(): Boolean =
        lastSyncMillis == 0L || System.currentTimeMillis() - lastSyncMillis > STALE_AFTER_MILLIS

    suspend fun sync(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.steampowered.com/ITwoFactorService/QueryTime/v1/")
                .post(FormBody.Builder().build())
                .build()
            val body = SteamHttp.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                response.body?.string().orEmpty()
            }

            val root = Json.parseToJsonElement(body) as? JsonObject
                ?: throw IOException("Unexpected time response")
            val inner = root["response"] as? JsonObject
                ?: throw IOException("Missing response object")
            val serverTime = (inner["server_time"] as? JsonPrimitive)?.content?.toLongOrNull()
                ?: throw IOException("Missing server_time")

            val nowMillis = System.currentTimeMillis()
            offsetSeconds = serverTime - nowMillis / 1000
            lastSyncMillis = nowMillis
            lastError = null
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = "Time sync failed: " + (e.message ?: e::class.java.simpleName)
            false
        }
    }
}
