package io.github.sma.core.steam

import io.github.sma.core.crypto.Jwt
import io.github.sma.core.mafile.SteamAccount
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

object SessionManager {
    private const val REFRESH_THRESHOLD_SECONDS = 300L

    private val cache = ConcurrentHashMap<String, String>()
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    suspend fun accessToken(acc: SteamAccount): String {
        val refreshToken = acc.refreshToken
        if (refreshToken == null || !Jwt.isUsableMobile(refreshToken, SteamTime.now())) {
            throw NeedsSignInException()
        }
        val steamId = acc.steamId
        return mutexes.getOrPut(steamId) { Mutex() }.withLock {
            val cached = cache[steamId]
            if (cached != null && (Jwt.secondsLeft(cached, SteamTime.now()) ?: 0L) > REFRESH_THRESHOLD_SECONDS) {
                return@withLock cached
            }
            val fresh = AuthApi.generateAccessToken(refreshToken, steamId)
            cache[steamId] = fresh
            fresh
        }
    }

    fun invalidate(steamId: String) {
        cache.remove(steamId)
    }

    fun clear() {
        cache.clear()
    }
}
