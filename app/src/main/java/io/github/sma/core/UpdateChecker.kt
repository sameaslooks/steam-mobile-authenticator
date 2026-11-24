package io.github.sma.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Asks GitHub once a day whether a newer release exists. Only reports it; nothing is downloaded or installed. */
object UpdateChecker {
    private const val API_URL = "https://api.github.com/repos/sameaslooks/steam-mobile-authenticator/releases/latest"
    private const val FALLBACK_URL = "https://github.com/sameaslooks/steam-mobile-authenticator/releases/latest"
    private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    private const val HTTP_TIMEOUT_MS = 15_000
    private const val PREFS = "sma_update"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val KEY_LATEST_TAG = "latest_tag"
    private const val KEY_LATEST_URL = "latest_url"
    private const val KEY_DISMISSED_TAG = "dismissed_tag"

    data class Update(val tag: String, val url: String)

    sealed class CheckResult {
        object UpToDate : CheckResult()
        data class Available(val update: Update) : CheckResult()
        data class Failed(val message: String) : CheckResult()
    }

    private val _available = MutableStateFlow<Update?>(null)

    /** Newer release than the installed one, not dismissed by the user. Null when there is nothing to show. */
    val available: StateFlow<Update?> = _available.asStateFlow()

    private val _lastCheckMs = MutableStateFlow(0L)

    /** Time of the last successful check in epoch millis; 0 when never checked. */
    val lastCheckMs: StateFlow<Long> = _lastCheckMs.asStateFlow()

    fun init(context: Context) {
        _lastCheckMs.value = prefs(context).getLong(KEY_LAST_CHECK, 0L)
        recompute(context)
    }

    /** Runs [checkNow] only if the last successful check is older than [CHECK_INTERVAL_MS]. Errors are ignored. */
    suspend fun maybeCheck(context: Context) {
        val last = prefs(context).getLong(KEY_LAST_CHECK, 0L)
        if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return
        checkNow(context)
    }

    suspend fun checkNow(context: Context): CheckResult = withContext(Dispatchers.IO) {
        try {
            val (tag, url) = fetchLatest(context)
            val now = System.currentTimeMillis()
            prefs(context).edit()
                .putLong(KEY_LAST_CHECK, now)
                .putString(KEY_LATEST_TAG, tag)
                .putString(KEY_LATEST_URL, url)
                .apply()
            _lastCheckMs.value = now
            recompute(context)
            if (isNewer(tag, installedVersion(context))) {
                CheckResult.Available(Update(tag, url))
            } else {
                CheckResult.UpToDate
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CheckResult.Failed(e.message ?: "Unexpected error")
        }
    }

    fun dismiss(context: Context, tag: String) {
        prefs(context).edit().putString(KEY_DISMISSED_TAG, tag).apply()
        recompute(context)
    }

    /** Blocking; call from a background dispatcher. */
    private fun fetchLatest(context: Context): Pair<String, String> {
        val conn = URL(API_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = HTTP_TIMEOUT_MS
            conn.readTimeout = HTTP_TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", appUserAgent(context))
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${conn.responseCode}")
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val json = Json.parseToJsonElement(text).jsonObject
            val tag = json["tag_name"]?.jsonPrimitive?.content ?: throw IOException("No release tag")
            val url = json["html_url"]?.jsonPrimitive?.content?.takeIf { it.startsWith("https://") } ?: FALLBACK_URL
            return tag to url
        } finally {
            conn.disconnect()
        }
    }

    private fun recompute(context: Context) {
        val prefs = prefs(context)
        val tag = prefs.getString(KEY_LATEST_TAG, null)
        val url = prefs.getString(KEY_LATEST_URL, null)
        val dismissed = prefs.getString(KEY_DISMISSED_TAG, null)
        _available.value = if (tag != null && url != null && tag != dismissed &&
            isNewer(tag, installedVersion(context))
        ) {
            Update(tag, url)
        } else {
            null
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun installedVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "unknown"

    /** True only if [latest] is strictly newer than [installed]. Non-numeric parts of [latest] count as 0. */
    private fun isNewer(latest: String, installed: String): Boolean {
        if (!isParsable(installed)) return false
        val a = versionParts(latest)
        val b = versionParts(installed)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** False for "unknown" or any installed version with a non-numeric part; such installs never get a notice. */
    private fun isParsable(raw: String): Boolean =
        raw != "unknown" && raw.trim()
            .trimStart('v', 'V')
            .substringBefore('-')
            .substringBefore('+')
            .split('.')
            .all { it.trim().toIntOrNull() != null }

    private fun versionParts(raw: String): List<Int> =
        raw.trim()
            .trimStart('v', 'V')
            .substringBefore('-')
            .substringBefore('+')
            .split('.')
            .map { it.trim().toIntOrNull() ?: 0 }
}
