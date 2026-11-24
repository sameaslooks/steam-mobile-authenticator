package io.github.sma.background

import android.content.Context
import io.github.sma.core.crypto.Jwt
import io.github.sma.core.mafile.SteamAccount
import io.github.sma.core.steam.AuthApi
import io.github.sma.core.steam.ConfirmationsApi
import io.github.sma.core.steam.NeedsSignInException
import io.github.sma.core.steam.SessionManager
import io.github.sma.core.steam.SteamTime
import io.github.sma.core.steam.userMessage
import io.github.sma.data.AccountRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

object ConfirmationMonitor {
    private val mutex = Mutex()

    // Separate lock for the lightweight login-request poll, so it never waits for a full refresh.
    private val loginMutex = Mutex()

    private val _pending = MutableStateFlow<Map<String, List<ConfirmationsApi.Confirmation>>>(emptyMap())
    val pending: StateFlow<Map<String, List<ConfirmationsApi.Confirmation>>> = _pending.asStateFlow()

    private val _loginRequests = MutableStateFlow<Map<String, Int>>(emptyMap())
    val loginRequests: StateFlow<Map<String, Int>> = _loginRequests.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /**
     * First failure of the last refresh (per-account text or loadError), null after a full success.
     * Set regardless of reportErrors, so background polls can show it in the service notification.
     */
    @Volatile
    var lastFailureMessage: String? = null

    /** Clears the reported error once the UI has shown it. */
    fun clearError() {
        _lastError.value = null
    }

    // Confirmation ids currently shown as notifications, per steamId (in-memory).
    private val shown = ConcurrentHashMap<String, MutableSet<String>>()

    // Guarded by mutex.
    private val signInNotified = HashSet<String>()
    private val notifiedLoginCounts = HashMap<String, Int>()

    /**
     * Polls all eligible accounts. Returns true when every polled account succeeded.
     * [reportErrors] = false (automatic/background polls): failures do not set [lastError];
     * a fully successful poll still clears it. User-initiated refreshes use the default (true).
     */
    suspend fun refresh(ctx: Context, notify: Boolean, reportErrors: Boolean = true): Boolean = mutex.withLock {
        val app = ctx.applicationContext
        AccountRepository.init(app)
        AccountRepository.load()
        AccountRepository.loadError.value?.let { error ->
            if (reportErrors) _lastError.value = error
            lastFailureMessage = error
            return@withLock false
        }
        if (SteamTime.isStale()) SteamTime.sync()

        val settings = AccountRepository.settings.value.notifications
        val accounts = AccountRepository.accounts.value
        val targets = accounts.filter { !notify || it.steamId !in settings.disabledAccounts }
        val errors = targets.mapNotNull { refreshAccount(app, it, notify) }

        val known = accounts.mapTo(HashSet()) { it.steamId }
        _pending.update { m -> m.filterKeys { it in known } }
        _loginRequests.update { m -> m.filterKeys { it in known } }
        if (reportErrors) {
            _lastError.value = errors.firstOrNull()
        } else if (errors.isEmpty()) {
            _lastError.value = null
        }
        lastFailureMessage = errors.firstOrNull()
        errors.isEmpty()
    }

    /**
     * Lightweight poll of pending sign-in request counts only: no confirmations, no notifications.
     * Per-account errors are ignored here; the full refresh reports them.
     */
    suspend fun refreshLoginRequests(ctx: Context): Unit = loginMutex.withLock {
        val app = ctx.applicationContext
        AccountRepository.init(app)
        if (AccountRepository.loadError.value != null) return@withLock
        if (SteamTime.isStale()) SteamTime.sync()

        val accounts = AccountRepository.accounts.value
        for (acc in accounts) {
            if (!hasUsableToken(acc)) continue
            try {
                val token = SessionManager.accessToken(acc)
                val count = AuthApi.pendingSessions(token).size
                _loginRequests.update { m -> m + (acc.steamId to count) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Swallowed on purpose: keep the previous count for this account.
            }
        }

        val known = accounts.mapTo(HashSet()) { it.steamId }
        _loginRequests.update { m -> m.filterKeys { it in known } }
    }

    /**
     * Approves or denies the given confirmations of one account. Fetches a fresh list when some
     * ids are not in the cached pending list. Throws if any id is no longer pending.
     */
    suspend fun respond(ctx: Context, steamId: String, confIds: List<String>, accept: Boolean) {
        mutex.withLock {
            val app = ctx.applicationContext
            AccountRepository.init(app)
            AccountRepository.load()
            val acc = AccountRepository.find(steamId) ?: throw IllegalArgumentException("Unknown account")

            val wanted = confIds.toSet()
            var items = _pending.value[steamId].orEmpty().filter { it.id in wanted }
            if (items.size < wanted.size) {
                val fresh = ConfirmationsApi.list(acc)
                _pending.update { m -> m + (steamId to fresh) }
                items = fresh.filter { it.id in wanted }
            }
            check(items.size == wanted.size) { "Confirmation is no longer pending" }

            ConfirmationsApi.respond(acc, items, accept)

            _pending.update { m -> m + (steamId to m[steamId].orEmpty().filterNot { it.id in wanted }) }
            val shownSet = shown[steamId]
            items.forEach { item ->
                Notifier.cancelConfirmation(app, steamId, item.id)
                shownSet?.remove(item.id)
            }
        }
    }

    /** Returns null on success, otherwise a short error description for this account. Caller holds mutex. */
    private suspend fun refreshAccount(app: Context, acc: SteamAccount, notify: Boolean): String? {
        if (!hasUsableToken(acc)) {
            notifySignIn(app, acc, notify)
            return "Sign in required for ${acc.accountName}"
        }
        return try {
            val confirmations = ConfirmationsApi.list(acc)
            val token = SessionManager.accessToken(acc)
            val loginCount = AuthApi.pendingSessions(token).size
            applyConfirmations(app, acc, confirmations, notify)
            applyLoginRequests(app, acc, loginCount, notify)
            signInNotified.remove(acc.steamId)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: NeedsSignInException) {
            notifySignIn(app, acc, notify)
            "Sign in required for ${acc.accountName}"
        } catch (e: Exception) {
            "${acc.accountName}: ${e.userMessage()}"
        }
    }

    private fun applyConfirmations(
        app: Context,
        acc: SteamAccount,
        confirmations: List<ConfirmationsApi.Confirmation>,
        notify: Boolean,
    ) {
        val ids = confirmations.map { it.id }
        val shownSet = shown.getOrPut(acc.steamId) { ConcurrentHashMap.newKeySet<String>() }

        if (notify && Notifier.canPost(app)) {
            val fresh = SeenStore.filterNew(app, acc.steamId, ids).toSet()
            confirmations.filter { it.id in fresh }.forEach { c ->
                Notifier.postConfirmation(app, acc.accountName, acc.steamId, c)
                shownSet.add(c.id)
            }
            SeenStore.markSeen(app, acc.steamId, ids)
        }

        shownSet.filterNot { it in ids }.forEach { id ->
            Notifier.cancelConfirmation(app, acc.steamId, id)
            shownSet.remove(id)
        }
        _pending.update { m -> m + (acc.steamId to confirmations) }
    }

    private fun applyLoginRequests(app: Context, acc: SteamAccount, count: Int, notify: Boolean) {
        if (notify && Notifier.canPost(app)) {
            val previous = notifiedLoginCounts[acc.steamId] ?: 0
            if (count > previous) Notifier.postLoginRequest(app, acc.accountName, acc.steamId, count)
            notifiedLoginCounts[acc.steamId] = count
        }
        _loginRequests.update { m -> m + (acc.steamId to count) }
    }

    private fun notifySignIn(app: Context, acc: SteamAccount, notify: Boolean) {
        if (!notify || !Notifier.canPost(app)) return
        if (!signInNotified.add(acc.steamId)) return
        Notifier.postStatus(
            app,
            "signin/${acc.steamId}".hashCode(),
            "Sign in required for ${acc.accountName}",
            "Open SMA to sign in again",
            "signin/${acc.steamId}",
        )
    }

    private fun hasUsableToken(acc: SteamAccount): Boolean {
        val token = acc.refreshToken ?: return false
        return Jwt.isUsableMobile(token, SteamTime.now())
    }
}
