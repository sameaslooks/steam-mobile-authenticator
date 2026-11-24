package io.github.sma.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.SslErrorHandler
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import io.github.sma.core.steam.NeedsSignInException
import io.github.sma.core.steam.SessionManager
import io.github.sma.core.steam.userMessage
import io.github.sma.data.AccountRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.SecureRandom
import kotlin.coroutines.resume

private const val DEFAULT_URL = "https://steamcommunity.com/my/"

private val STEAM_URLS = listOf(
    "https://steamcommunity.com",
    "https://store.steampowered.com",
    "https://help.steampowered.com",
)

private val ALLOWED_HOSTS = setOf(
    "steamcommunity.com",
    "store.steampowered.com",
    "help.steampowered.com",
    "checkout.steampowered.com",
    "login.steampowered.com",
)

private val ALLOWED_ROOT_DOMAINS = listOf("steamcommunity.com", "steampowered.com")

private const val MOBILE_SCHEME = "steammobile"

private data class QuickLink(val label: String, val url: String)

private val QUICK_LINKS = listOf(
    QuickLink("Trade offers", "https://steamcommunity.com/my/tradeoffers/"),
    QuickLink("Inventory", "https://steamcommunity.com/my/inventory/"),
    QuickLink("Market", "https://steamcommunity.com/market/"),
    QuickLink("Profile", "https://steamcommunity.com/my/"),
)

private sealed interface Phase {
    object Preparing : Phase
    class Failed(val message: String, val needsSignIn: Boolean) : Phase
    object Ready : Phase
}

/** Holds the WebView without triggering recomposition; accessed only from click handlers and dispose. */
private class WebViewRef {
    var view: WebView? = null
}

/** True if the URL may be loaded inside the in-app WebView. */
internal fun isInAppUrl(uri: Uri): Boolean {
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    val host = uri.host?.lowercase() ?: return false
    if (host in ALLOWED_HOSTS) return true
    return ALLOWED_ROOT_DOMAINS.any { host == it || host.endsWith(".$it") }
}

internal fun openExternal(context: Context, uri: Uri) {
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (e: ActivityNotFoundException) {
        // No browser available; nothing to do.
    }
}

private fun randomHex(byteCount: Int): String {
    val buf = ByteArray(byteCount)
    SecureRandom().nextBytes(buf)
    return buf.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

private suspend fun CookieManager.removeAllCookiesSuspend() {
    suspendCancellableCoroutine<Unit> { cont ->
        removeAllCookies { _ -> if (cont.isActive) cont.resume(Unit) }
    }
}

/** Replaces all cookies with the Steam mobile session before any page is loaded. */
internal suspend fun prepareSteamCookies(steamId: String, token: String) {
    val cm = CookieManager.getInstance()
    cm.removeAllCookiesSuspend()
    val sessionId = randomHex(12) // 24 hex chars
    for (url in STEAM_URLS) {
        cm.setCookie(url, "steamLoginSecure=${steamId}%7C%7C${token}; Path=/; Secure; HttpOnly")
        cm.setCookie(url, "sessionid=$sessionId; Path=/; Secure")
        cm.setCookie(url, "mobileClient=android; Path=/")
        cm.setCookie(url, "mobileClientVersion=777777 3.10.3; Path=/")
        cm.setCookie(url, "Steam_Language=english; Path=/")
    }
    cm.flush()
}

@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION")
private fun newWebView(
    context: Context,
    onTitle: (String) -> Unit,
    onProgress: (Int) -> Unit,
    onNavState: (canBack: Boolean, canForward: Boolean, url: String?) -> Unit,
): WebView {
    val webView = WebView(context)
    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)

    // Downloads are ignored.
    webView.setDownloadListener { _, _, _, _, _ -> }

    val s = webView.settings
    s.javaScriptEnabled = true
    s.domStorageEnabled = true
    s.allowFileAccess = false
    s.allowContentAccess = false
    s.setGeolocationEnabled(false)
    s.saveFormData = false
    s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
        WebSettingsCompat.setSafeBrowsingEnabled(s, true)
    }
    s.userAgentString = "${s.userAgentString} Valve Steam App Version/3"

    webView.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val scheme = uri.scheme?.lowercase()
            return when {
                scheme == MOBILE_SCHEME -> true
                scheme == "https" && isInAppUrl(uri) -> false
                scheme == "http" || scheme == "https" -> {
                    openExternal(context, uri)
                    true
                }
                else -> true
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            onNavState(view.canGoBack(), view.canGoForward(), url)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            onNavState(view.canGoBack(), view.canGoForward(), url)
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
        }
    }

    webView.webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            onProgress(newProgress)
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            onTitle(title.orEmpty())
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean = false
    }

    return webView
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(steamId: String, startUrl: String, nav: NavController) {
    val context = LocalContext.current
    val holder = remember(steamId) { WebViewRef() }

    var phase by remember(steamId) { mutableStateOf<Phase>(Phase.Preparing) }
    var attempt by remember(steamId) { mutableStateOf(0) }
    var pageTitle by remember(steamId) { mutableStateOf("") }
    var currentUrl by remember(steamId) { mutableStateOf("") }
    var progress by remember(steamId) { mutableStateOf(0) }
    var canGoBack by remember(steamId) { mutableStateOf(false) }
    var canGoForward by remember(steamId) { mutableStateOf(false) }

    val initialUrl = remember(startUrl) {
        if (isInAppUrl(Uri.parse(startUrl))) startUrl else DEFAULT_URL
    }

    LaunchedEffect(steamId, attempt) {
        phase = Phase.Preparing
        try {
            AccountRepository.load()
            val account = AccountRepository.find(steamId)
                ?: throw IllegalStateException(AccountRepository.loadError.value ?: "Account not found")
            val token = SessionManager.accessToken(account)
            prepareSteamCookies(steamId, token)
            phase = Phase.Ready
        } catch (e: CancellationException) {
            throw e
        } catch (e: NeedsSignInException) {
            phase = Phase.Failed(e.userMessage(), needsSignIn = true)
        } catch (e: Exception) {
            phase = Phase.Failed(e.userMessage(), needsSignIn = false)
        }
    }

    DisposableEffect(steamId) {
        onDispose {
            holder.view?.let { wv ->
                wv.stopLoading()
                wv.clearHistory()
                wv.clearCache(true)
                wv.destroy()
            }
            holder.view = null
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
            WebStorage.getInstance().deleteAllData()
        }
    }

    BackHandler(enabled = canGoBack) {
        holder.view?.goBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = pageTitle.ifBlank { "Steam" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (canGoBack) holder.view?.goBack() else nav.popBackStack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { holder.view?.goForward() },
                        enabled = canGoForward,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
                    }
                    IconButton(
                        onClick = { holder.view?.reload() },
                        enabled = phase == Phase.Ready,
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reload")
                    }
                    TextButton(
                        onClick = { openExternal(context, Uri.parse(currentUrl)) },
                        enabled = currentUrl.isNotBlank(),
                    ) {
                        Text("Browser")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val p = phase) {
                Phase.Preparing -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                is Phase.Failed -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = p.message, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    if (p.needsSignIn) {
                        Button(onClick = { nav.navigate("signin/$steamId") }) { Text("Sign in") }
                    } else {
                        Button(onClick = { attempt++ }) { Text("Retry") }
                    }
                }

                Phase.Ready -> Column(modifier = Modifier.fillMaxSize()) {
                    if (progress < 100) {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(QUICK_LINKS) { link ->
                            AssistChip(
                                onClick = { holder.view?.loadUrl(link.url) },
                                label = { Text(link.label) },
                            )
                        }
                    }
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            newWebView(
                                context = ctx,
                                onTitle = { pageTitle = it },
                                onProgress = { progress = it },
                                onNavState = { back, forward, url ->
                                    canGoBack = back
                                    canGoForward = forward
                                    if (url != null) currentUrl = url
                                },
                            ).also { wv ->
                                holder.view = wv
                                wv.loadUrl(initialUrl)
                            }
                        },
                    )
                }
            }
        }
    }
}
