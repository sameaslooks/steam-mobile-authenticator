package io.github.sma.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.navigation.NavController
import io.github.sma.core.steam.AuthApi
import io.github.sma.core.steam.NeedsSignInException
import io.github.sma.core.steam.SessionManager
import io.github.sma.core.steam.SteamApiException
import io.github.sma.core.steam.userMessage
import io.github.sma.data.AccountRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.IOException

private val EXPIRED_ERESULTS = setOf(9, 42)

private data class LoginRequest(val clientId: Long, val info: AuthApi.AuthSessionInfo)

/**
 * Lists pending Steam sign-in requests for [steamId] and lets the user approve or deny them.
 * When [clientId] is set (opened from a scanned QR code) only that request is shown and the
 * screen is popped after a successful approval.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginRequestsScreen(
    steamId: String,
    nav: NavController,
    clientId: Long? = null,
    version: Int? = null,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val settings by AccountRepository.settings.collectAsState()

    var requests by remember { mutableStateOf<List<LoginRequest>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var needsSignIn by remember { mutableStateOf(false) }
    var busyClientIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var approvalToConfirm by remember { mutableStateOf<LoginRequest?>(null) }

    fun showMessage(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    fun refresh() {
        if (loading) return
        scope.launch {
            loading = true
            try {
                requests = fetchRequests(steamId, clientId, version)
                errorText = null
                needsSignIn = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: NeedsSignInException) {
                needsSignIn = true
                requests = emptyList()
            } catch (e: Exception) {
                errorText = describeError(e)
            } finally {
                loaded = true
                loading = false
            }
        }
    }

    fun respond(request: LoginRequest, approve: Boolean) {
        scope.launch {
            busyClientIds = busyClientIds + request.clientId
            try {
                respondToRequest(steamId, request, approve)
                val message = if (approve) "Sign-in approved" else "Denied"
                if (clientId != null) {
                    nav.popBackStack()
                } else {
                    requests = requests.filterNot { it.clientId == request.clientId }
                }
                showMessage(message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NeedsSignInException) {
                needsSignIn = true
            } catch (e: SteamApiException) {
                if (e.eresult in EXPIRED_ERESULTS) {
                    requests = requests.filterNot { it.clientId == request.clientId }
                }
                showMessage(describeError(e))
            } catch (e: Exception) {
                showMessage(describeError(e))
            } finally {
                busyClientIds = busyClientIds - request.clientId
            }
        }
    }

    fun onApproveClick(request: LoginRequest) {
        if (request.info.locationMismatch) {
            approvalToConfirm = request
        } else {
            respond(request, approve = true)
        }
    }

    LaunchedEffect(steamId, clientId, version) {
        refresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sign-in requests") },
                actions = {
                    TextButton(onClick = { refresh() }, enabled = !loading) { Text("Refresh") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = { refresh() },
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (needsSignIn) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("Sign in required", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { nav.navigate("signin/$steamId") }) {
                                Text("Sign in")
                            }
                        }
                    }
                } else if (errorText != null) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = errorText.orEmpty(),
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = { refresh() }) { Text("Retry") }
                        }
                    }
                } else if (loaded && requests.isEmpty()) {
                    item {
                        Text(
                            text = "No pending sign-in requests",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                items(requests, key = { it.clientId }) { request ->
                    LoginRequestCard(
                        request = request,
                        busy = request.clientId in busyClientIds,
                        showMap = settings.showSignInMap,
                        onApprove = { onApproveClick(request) },
                        onDeny = { respond(request, approve = false) },
                    )
                }
            }
        }
    }

    approvalToConfirm?.let { target ->
        AlertDialog(
            onDismissRequest = { approvalToConfirm = null },
            title = { Text("Approve sign-in from another location?") },
            text = {
                Text(
                    "This request comes from a different location than your phone. " +
                        "Approve only if you started this sign-in yourself."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    approvalToConfirm = null
                    respond(target, approve = true)
                }) { Text("Approve") }
            },
            dismissButton = {
                TextButton(onClick = { approvalToConfirm = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun LoginRequestCard(
    request: LoginRequest,
    busy: Boolean,
    showMap: Boolean,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    val info = request.info
    val context = LocalContext.current
    val location = listOf(info.city, info.state, info.country)
        .mapNotNull { it?.trim()?.takeIf { s -> s.isNotEmpty() } }
        .joinToString(", ")
    var coords by remember(request.clientId) { mutableStateOf<Pair<Double, Double>?>(null) }
    LaunchedEffect(request.clientId, showMap) {
        coords = if (showMap) GeoLookup.locate(context, info) else null
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = info.deviceName?.takeIf { it.isNotBlank() } ?: "Unknown device",
                style = MaterialTheme.typography.titleMedium,
            )
            Text("Platform: ${platformLabel(info.platformType)}")
            info.ip?.takeIf { it.isNotBlank() }?.let { Text("IP: $it") }
            coords?.let { (lat, lon) ->
                SignInMap(lat = lat, lon = lon, modifier = Modifier.fillMaxWidth())
            }
            if (location.isNotEmpty()) Text("Location: $location")
            if (coords == null) {
                info.geoloc?.takeIf { it.isNotBlank() }?.let { Text("Geolocation: $it") }
            }

            if (info.locationMismatch) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        text = "This request comes from a different location than your phone",
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            if (info.highUsage) {
                Text(
                    text = "High usage: Steam reports many recent sign-in requests for this account.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                }
                OutlinedButton(onClick = onDeny, enabled = !busy) { Text("Deny") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onApprove, enabled = !busy) { Text("Approve") }
            }
        }
    }
}

private suspend fun fetchRequests(steamId: String, clientId: Long?, version: Int?): List<LoginRequest> {
    AccountRepository.load()
    AccountRepository.loadError.value?.let { throw IOException(it) }
    val account = AccountRepository.find(steamId) ?: throw IOException("Account not found")
    val token = SessionManager.accessToken(account)
    val ids = if (clientId != null) listOf(clientId) else AuthApi.pendingSessions(token)
    return coroutineScope {
        ids.map { id ->
            async {
                val info = AuthApi.sessionInfo(token, id)
                // Fall back to the version from the QR link if Steam did not report one.
                val resolved = if (info.version == 0 && version != null) info.copy(version = version) else info
                LoginRequest(id, resolved)
            }
        }.awaitAll()
    }
}

private suspend fun respondToRequest(steamId: String, request: LoginRequest, approve: Boolean) {
    val account = AccountRepository.find(steamId) ?: throw IOException("Account not found")
    val token = SessionManager.accessToken(account)
    AuthApi.respondToSession(token, account, request.info.version, request.clientId, approve)
}

private fun platformLabel(type: Int?): String = when (type) {
    1 -> "Steam client"
    2 -> "Web browser"
    3 -> "Mobile app"
    else -> "Unknown"
}

private fun describeError(e: Throwable): String = when (e) {
    is SteamApiException ->
        if (e.eresult in EXPIRED_ERESULTS) "Request expired or already handled"
        else e.userMessage()
    else -> e.userMessage()
}
