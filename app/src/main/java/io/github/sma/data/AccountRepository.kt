package io.github.sma.data

import android.content.Context
import io.github.sma.core.mafile.SteamAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

object AccountRepository {
    private val mutex = Mutex()

    private val _accounts = MutableStateFlow<List<SteamAccount>>(emptyList())
    val accounts: StateFlow<List<SteamAccount>> = _accounts.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    // Guarded by mutex (except vault, set once in init; loaded is volatile for isLoaded()).
    private var vault: Vault? = null
    private var data = VaultData()
    @Volatile private var loaded = false

    @Synchronized
    fun init(context: Context) {
        if (vault != null) return
        vault = Vault(context.applicationContext)
    }

    private fun requireVault(): Vault =
        checkNotNull(vault) { "AccountRepository.init(context) was not called" }

    fun isLoaded(): Boolean = loaded

    /**
     * Loads the vault once. Safe to call from a background worker; returns immediately if already loaded.
     * On failure [loadError] is set and mutations are refused,
     * so a corrupted vault file is never overwritten.
     * A legacy (format v1) vault is re-saved in format v2 right after a successful load.
     */
    suspend fun load() {
        mutex.withLock {
            if (loaded) return
            val v = requireVault()
            try {
                val loadedData = withContext(Dispatchers.IO) { v.load() }
                data = loadedData
                _accounts.value = loadedData.accounts
                _settings.value = loadedData.settings
                _loadError.value = null
                loaded = true
            } catch (e: VaultCorruptedException) {
                _loadError.value = e.message ?: "Vault is corrupted"
                return
            } catch (e: IOException) {
                _loadError.value = "Cannot read vault: ${e.message}"
                return
            }
            migrateIfNeeded(v)
        }
    }

    /** Best effort: on failure the vault stays readable and migration is retried on the next app start. */
    private suspend fun migrateIfNeeded(v: Vault) {
        try {
            val needs = withContext(Dispatchers.IO) { v.needsMigration() }
            if (needs) {
                withContext(Dispatchers.IO) { v.save(data) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep the loaded vault; migration will be retried on the next start.
        }
    }

    suspend fun upsert(acc: SteamAccount) {
        mutate { list ->
            if (list.any { it.steamId == acc.steamId }) {
                list.map { if (it.steamId == acc.steamId) acc else it }
            } else {
                list + acc
            }
        }
    }

    suspend fun remove(steamId: String) {
        mutate { list -> list.filterNot { it.steamId == steamId } }
    }

    suspend fun updateRefreshToken(steamId: String, refreshToken: String?) {
        mutate { list ->
            list.map {
                if (it.steamId == steamId && it.refreshToken != refreshToken) {
                    it.copy(refreshToken = refreshToken)
                } else {
                    it
                }
            }
        }
    }

    /** Applies [transform] to the current settings, persists the result and publishes it via [settings]. */
    suspend fun updateSettings(transform: (Settings) -> Settings) {
        mutex.withLock {
            check(loaded) { "Vault not loaded: ${_loadError.value ?: "load() not called"}" }
            val updated = transform(data.settings)
            if (updated == data.settings) return
            val newData = data.copy(settings = updated)
            withContext(Dispatchers.IO) { requireVault().save(newData) }
            data = newData
            _settings.value = updated
        }
    }

    fun find(steamId: String): SteamAccount? =
        _accounts.value.firstOrNull { it.steamId == steamId }

    private suspend fun mutate(transform: (List<SteamAccount>) -> List<SteamAccount>) {
        mutex.withLock {
            check(loaded) { "Vault not loaded: ${_loadError.value ?: "load() not called"}" }
            val updated = transform(data.accounts)
            if (updated == data.accounts) return
            val newData = data.copy(accounts = updated)
            withContext(Dispatchers.IO) { requireVault().save(newData) }
            data = newData
            _accounts.value = updated
        }
    }
}
