package io.github.sma.core.steam

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/** Public Steam Community profile lookup. Only the persona name is read; no cookies are sent. */
object ProfileApi {
    private val NAME_REGEX = Regex(
        "<steamID>\\s*(?:<!\\[CDATA\\[(.*?)\\]\\]>|([^<]*))\\s*</steamID>",
        RegexOption.DOT_MATCHES_ALL,
    )

    /** Returns the trimmed nickname, or null on any error or when the name is empty. */
    suspend fun nickname(steamId: String): String? = withContext(Dispatchers.IO) {
        if (steamId.isEmpty() || !steamId.all { it.isDigit() }) return@withContext null
        try {
            val request = Request.Builder()
                .url("https://steamcommunity.com/profiles/$steamId/?xml=1")
                .get()
                .build()
            SteamHttp.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val match = NAME_REGEX.find(body) ?: return@use null
                val cdata = match.groups[1]?.value
                val raw = cdata ?: unescapeXml(match.groups[2]?.value.orEmpty())
                raw.trim().ifEmpty { null }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun unescapeXml(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
