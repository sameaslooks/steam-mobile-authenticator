package io.github.sma.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import io.github.sma.core.crypto.Secrets
import io.github.sma.core.crypto.SteamTotp
import io.github.sma.core.mafile.SteamAccount
import io.github.sma.core.steam.SteamTime
import io.github.sma.data.NicknameCache
import kotlinx.coroutines.delay

/** Walks the ContextWrapper chain to find the hosting FragmentActivity (needed for BiometricPrompt). */
fun Context.findFragmentActivity(): FragmentActivity? {
    var ctx: Context? = this
    while (ctx != null) {
        if (ctx is FragmentActivity) return ctx
        ctx = (ctx as? ContextWrapper)?.baseContext
    }
    return null
}

/**
 * Live Steam Guard code with a 30-second progress bar. Ticks every second.
 * Tapping the code calls [onCopy] with the raw code, if provided.
 */
@Composable
fun GuardCode(
    account: SteamAccount,
    modifier: Modifier = Modifier,
    onCopy: ((String) -> Unit)? = null,
) {
    var now by remember { mutableLongStateOf(SteamTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SteamTime.now()
            delay(1_000L)
        }
    }

    val secret = remember(account.sharedSecret) {
        runCatching { Secrets.decode(account.sharedSecret) }.getOrNull()
    }
    val code: String? = secret?.let { runCatching { SteamTotp.code(it, now) }.getOrNull() }
    val remaining = SteamTotp.secondsRemaining(now)

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = code ?: "Invalid secret",
            style = if (code != null) {
                MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.titleMedium
            },
            color = if (code != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            modifier = Modifier.clickable(enabled = code != null && onCopy != null) {
                if (code != null) onCopy?.invoke(code)
            },
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            LinearProgressIndicator(
                progress = remaining / 30f,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "${remaining}s",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Display title for an account: "login (nick)", or just the login when the nickname is unknown,
 * blank or equal to the login. Triggers a (24 h throttled) nickname refresh for [account].
 */
@Composable
fun accountTitle(account: SteamAccount): String {
    val context = LocalContext.current
    val names by NicknameCache.names.collectAsState()
    LaunchedEffect(account.steamId) {
        NicknameCache.refresh(context, account.steamId)
    }
    val login = account.accountName
    val nick = names[account.steamId]?.trim()
    return if (nick.isNullOrBlank() || nick.equals(login, ignoreCase = true)) {
        login
    } else {
        "$login ($nick)"
    }
}
