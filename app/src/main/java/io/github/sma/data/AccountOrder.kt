package io.github.sma.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Account list ordering, persisted in SharedPreferences "sma_prefs".
 * The manual order is kept when the sort mode changes, so returning to [MANUAL] restores it.
 */
object AccountOrder {
    const val MANUAL = "MANUAL"
    const val LOGIN = "LOGIN"
    const val NICKNAME = "NICKNAME"
    const val ADDED = "ADDED"
    const val PENDING_FIRST = "PENDING_FIRST"

    private const val DEFAULT_MODE = LOGIN
    private const val PREFS = "sma_prefs"
    private const val KEY_SORT_MODE = "sort_mode"
    private const val KEY_MANUAL_ORDER = "manual_order"
    private val ALL_MODES = setOf(MANUAL, LOGIN, NICKNAME, ADDED, PENDING_FIRST)

    private val lock = Any()
    private var loaded = false // guarded by lock

    private val _sortMode = MutableStateFlow(DEFAULT_MODE)
    val sortMode: StateFlow<String> = _sortMode.asStateFlow()

    private val _manualOrder = MutableStateFlow<List<String>>(emptyList())
    val manualOrder: StateFlow<List<String>> = _manualOrder.asStateFlow()

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Reads the persisted sort mode and manual order once. Later calls are no-ops. */
    fun load(ctx: Context) {
        synchronized(lock) {
            if (loaded) return
            val p = prefs(ctx)
            _sortMode.value = p.getString(KEY_SORT_MODE, null)?.takeIf { it in ALL_MODES } ?: DEFAULT_MODE
            _manualOrder.value = parseIds(p.getString(KEY_MANUAL_ORDER, null))
            loaded = true
        }
    }

    fun setMode(ctx: Context, mode: String) {
        require(mode in ALL_MODES) { "Unknown sort mode: $mode" }
        load(ctx)
        prefs(ctx).edit().putString(KEY_SORT_MODE, mode).apply()
        _sortMode.value = mode
    }

    /** [current] is the full list of steamIds in the current manual order. */
    fun moveUp(ctx: Context, steamId: String, current: List<String>) {
        val i = current.indexOf(steamId)
        if (i <= 0) return
        persist(ctx, current.swapped(i, i - 1))
    }

    /** [current] is the full list of steamIds in the current manual order. */
    fun moveDown(ctx: Context, steamId: String, current: List<String>) {
        val i = current.indexOf(steamId)
        if (i < 0 || i >= current.lastIndex) return
        persist(ctx, current.swapped(i, i + 1))
    }

    /**
     * Saved ids that still exist, in saved order, followed by ids that are not saved yet, in [ids] (vault) order.
     */
    fun manualOrder(ids: List<String>, saved: List<String>): List<String> {
        val present = ids.toSet()
        val kept = saved.distinct().filter { it in present }
        val keptSet = kept.toSet()
        return kept + ids.filter { it !in keptSet }
    }

    private fun persist(ctx: Context, order: List<String>) {
        load(ctx)
        val ids = order.distinct()
        prefs(ctx).edit().putString(KEY_MANUAL_ORDER, ids.joinToString(",")).apply()
        _manualOrder.value = ids
    }

    private fun parseIds(raw: String?): List<String> =
        raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun List<String>.swapped(a: Int, b: Int): List<String> =
        toMutableList().apply {
            val tmp = this[a]
            this[a] = this[b]
            this[b] = tmp
        }
}
