package io.github.sma.ui

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import io.github.sma.background.ConfirmationMonitor
import io.github.sma.core.steam.ConfirmationsApi
import io.github.sma.core.steam.NeedsSignInException
import io.github.sma.core.steam.SessionManager
import io.github.sma.core.steam.userMessage
import io.github.sma.data.AccountRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

private const val DETAILS_BASE_URL = "https://steamcommunity.com/mobileconf/"

private sealed interface DetailsPhase {
    object Loading : DetailsPhase
    class Failed(val message: String, val needsSignIn: Boolean) : DetailsPhase
    class Ready(val conf: ConfirmationsApi.Confirmation, val html: String) : DetailsPhase
}

/** Holds the WebView without triggering recomposition; accessed only from the factory and dispose. */
private class DetailsWebViewRef {
    var view: WebView? = null
}

private fun detailsTypeTitle(c: ConfirmationsApi.Confirmation): String = when (c.type) {
    2 -> "Trade"
    3 -> "Market listing"
    else -> c.typeName
}

private fun wrapDetailsHtml(html: String): String =
    "<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'>" +
        "<style>body{background:#1b2838;color:#c7d5e0;font-family:sans-serif;margin:12px} " +
        "a{color:#66c0f4} img{max-width:100%}</style></head><body>" +
        html +
        "</body></html>"

/** Resolves the confirmation, sets the Steam session cookies and fetches Steam's details HTML. */
private suspend fun loadDetails(context: Context, steamId: String, confId: String): DetailsPhase.Ready {
    AccountRepository.init(context.applicationContext)
    AccountRepository.load()
    val account = AccountRepository.find(steamId)
        ?: throw IOException(AccountRepository.loadError.value ?: "Account not found")
    val conf = ConfirmationMonitor.pending.value[steamId].orEmpty().firstOrNull { it.id == confId }
        ?: ConfirmationsApi.list(account).firstOrNull { it.id == confId }
        ?: throw IOException("Confirmation is no longer pending")
    prepareSteamCookies(steamId, SessionManager.accessToken(account))
    return DetailsPhase.Ready(conf, ConfirmationsApi.details(account, confId))
}

@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION")
private fun newDetailsWebView(context: Context, onNavigate: (Uri) -> Unit): WebView {
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
        // The details page is static: every navigation is handed to the host and never loaded here.
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            onNavigate(request.url)
            return true
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
        }
    }

    return webView
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmationDetailsScreen(steamId: String, confId: String, nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val holder = remember(steamId, confId) { DetailsWebViewRef() }

    var phase by remember(steamId, confId) { mutableStateOf<DetailsPhase>(DetailsPhase.Loading) }
    var attempt by remember(steamId, confId) { mutableStateOf(0) }
    var working by remember(steamId, confId) { mutableStateOf(false) }
    var actionError by remember(steamId, confId) { mutableStateOf<String?>(null) }
    var askAccept by remember(steamId, confId) { mutableStateOf(false) }

    LaunchedEffect(steamId, confId, attempt) {
        phase = DetailsPhase.Loading
        phase = try {
            loadDetails(context, steamId, confId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: NeedsSignInException) {
            DetailsPhase.Failed("Sign in required", needsSignIn = true)
        } catch (e: Exception) {
            DetailsPhase.Failed(e.userMessage(), needsSignIn = false)
        }
    }

    DisposableEffect(steamId, confId) {
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

    val onNavigate: (Uri) -> Unit = { uri ->
        if (isInAppUrl(uri)) {
            nav.navigate(Routes.browser(steamId, uri.toString()))
        } else {
            openExternal(context, uri)
        }
    }

    fun respond(conf: ConfirmationsApi.Confirmation, accept: Boolean) {
        if (working) return
        scope.launch {
            working = true
            actionError = null
            try {
                ConfirmationMonitor.respond(context, steamId, listOf(conf.id), accept)
                nav.popBackStack()
            } catch (e: CancellationException) {
                throw e
            } catch (e: NeedsSignInException) {
                phase = DetailsPhase.Failed("Sign in required", needsSignIn = true)
            } catch (e: Exception) {
                actionError = e.userMessage()
            } finally {
                working = false
            }
        }
    }

    val ready = phase as? DetailsPhase.Ready

    if (askAccept && ready != null) {
        AlertDialog(
            onDismissRequest = { askAccept = false },
            title = { Text("Account security") },
            text = {
                Text("This is an account-security confirmation: ${ready.conf.headline}. Continue?")
            },
            confirmButton = {
                TextButton(onClick = {
                    askAccept = false
                    respond(ready.conf, true)
                }) {
                    Text("Accept")
                }
            },
            dismissButton = {
                TextButton(onClick = { askAccept = false }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ready?.let { detailsTypeTitle(it.conf) } ?: "Confirmation") },
                navigationIcon = {
                    TextButton(onClick = { nav.popBackStack() }) { Text("Back") }
                },
            )
        },
        bottomBar = {
            if (ready != null) {
                val conf = ready.conf
                val batchable = conf.type == 2 || conf.type == 3
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    actionError?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (conf.type == 2) {
                            TextButton(onClick = {
                                nav.navigate(
                                    Routes.browser(steamId, "https://steamcommunity.com/tradeoffer/${conf.creatorId}/"),
                                )
                            }) {
                                Text("Open trade offer")
                            }
                        }
                        TextButton(
                            onClick = { respond(conf, false) },
                            enabled = !working,
                        ) {
                            Text("Decline")
                        }
                        Button(
                            onClick = {
                                if (batchable) respond(conf, true) else askAccept = true
                            },
                            enabled = !working,
                        ) {
                            Text("Accept")
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when (val p = phase) {
                DetailsPhase.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                is DetailsPhase.Failed -> Column(
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

                is DetailsPhase.Ready -> AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        newDetailsWebView(ctx, onNavigate).also { wv ->
                            holder.view = wv
                            wv.loadDataWithBaseURL(
                                DETAILS_BASE_URL,
                                wrapDetailsHtml(p.html),
                                "text/html",
                                "utf-8",
                                null,
                            )
                        }
                    },
                )
            }
        }
    }
}
