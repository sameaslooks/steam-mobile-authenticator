package io.github.sma.data

import android.content.Context
import io.github.sma.core.steam.ProfileApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Cache of Steam persona names keyed by SteamID. Non-secret data, stored in plain SharedPreferences.
 * [names] is observed by the UI so composables recompose when a nickname arrives.
 */
object NicknameCache {
    private const val PREFS = "sma_nicknames"
    private const val NAME_PREFIX = "name_"
    private const val FETCHED_PREFIX = "fetched_"
    private const val TTL_MS = 24L * 60 * 60 * 1000

    val names = MutableStateFlow<Map<String, String>>(emptyMap())

    private val lock = Any()
    private val inFlight = HashSet<String>() // guarded by lock
    @Volatile private var loaded = false

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Loads persisted nicknames into [names] once. */
    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val stored = HashMap<String, String>()
            for ((key, value) in prefs(ctx).all) {
                if (key.startsWith(NAME_PREFIX) && value is String && value.isNotBlank()) {
                    stored[key.removePrefix(NAME_PREFIX)] = value
                }
            }
            names.update { current -> stored + current }
            loaded = true
        }
    }

    fun get(ctx: Context, steamId: String): String? {
        ensureLoaded(ctx)
        return names.value[steamId]
    }

    /**
     * Fetches the nickname unless the cached value is younger than 24 h (or [force] is set).
     * On failure the previous value and timestamp are left untouched.
     */
    suspend fun refresh(ctx: Context, steamId: String, force: Boolean = false) {
        ensureLoaded(ctx)
        val prefs = prefs(ctx)
        if (!force) {
            val last = prefs.getLong(FETCHED_PREFIX + steamId, 0L)
            if (System.currentTimeMillis() - last < TTL_MS) return
        }
        if (!beginFetch(steamId)) return
        try {
            val nick = ProfileApi.nickname(steamId) ?: return
            prefs.edit()
                .putString(NAME_PREFIX + steamId, nick)
                .putLong(FETCHED_PREFIX + steamId, System.currentTimeMillis())
                .apply()
            names.update { it + (steamId to nick) }
        } finally {
            endFetch(steamId)
        }
    }

    /** Returns false when a fetch for [steamId] is already running. */
    private fun beginFetch(steamId: String): Boolean = synchronized(lock) { inFlight.add(steamId) }

    private fun endFetch(steamId: String) {
        synchronized(lock) { inFlight.remove(steamId) }
    }
}
