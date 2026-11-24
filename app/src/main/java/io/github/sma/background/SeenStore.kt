package io.github.sma.background

import android.content.Context

/** Remembers which confirmation ids were already notified. Stores ids only, no secrets. */
object SeenStore {
    private const val PREFS = "sma_seen"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(steamId: String) = "seen_$steamId"

    @Synchronized
    fun filterNew(ctx: Context, steamId: String, ids: List<String>): List<String> {
        val seen: Set<String> = prefs(ctx).getStringSet(key(steamId), null) ?: emptySet()
        return ids.filterNot { it in seen }
    }

    /** Replaces the stored set with [ids] (the full list of currently pending ids), which prunes ids no longer pending. */
    @Synchronized
    fun markSeen(ctx: Context, steamId: String, ids: List<String>) {
        prefs(ctx).edit().putStringSet(key(steamId), ids.toSet()).apply()
    }
}
