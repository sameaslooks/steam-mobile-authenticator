package io.github.sma.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import io.github.sma.core.mafile.SteamAccount
import io.github.sma.core.steam.ConfirmationsApi
import io.github.sma.core.steam.NeedsSignInException
import io.github.sma.core.steam.SteamTime
import io.github.sma.core.steam.userMessage
import io.github.sma.data.AccountRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

private const val AUTO_REFRESH_MILLIS = 30_000L

/** Trade offers (2) and market listings (3) may be accepted or declined in batches. */
private val BATCHABLE_TYPES = setOf(2, 3)

private fun isBatchable(c: ConfirmationsApi.Confirmation): Boolean = c.type in BATCHABLE_TYPES

private class PendingAction(
    val items: List<ConfirmationsApi.Confirmation>,
    val accept: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmationsScreen(steamId: String, nav: NavController) {
    val account: SteamAccount? = remember(steamId) { AccountRepository.find(steamId) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val loadMutex = remember { Mutex() }

    var confirmations by remember { mutableStateOf<List<ConfirmationsApi.Confirmation>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var needsSignIn by remember { mutableStateOf(false) }
    var nowSeconds by remember { mutableStateOf(SteamTime.now()) }
    var pullRefreshing by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var pending by remember { mutableStateOf<PendingAction?>(null) }

    fun showMessage(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    if (account == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Confirmations") },
                    navigationIcon = {
                        TextButton(onClick = { nav.popBackStack() }) { Text("Back") }
                    },
                )
            },
        ) { innerPadding ->
            Column(modifier = Modifier.padding(innerPadding).padding(16.dp)) {
                Text("Account not found")
            }
        }
    } else {
        suspend fun load(acc: SteamAccount) {
            loadMutex.lock()
            try {
                val fresh = ConfirmationsApi.list(acc)
                confirmations = fresh
                val batchableIds = fresh.filter(::isBatchable).map { it.id }.toSet()
                selected = selected.intersect(batchableIds)
                loaded = true
                needsSignIn = false
                nowSeconds = SteamTime.now()
            } catch (e: CancellationException) {
                throw e
            } catch (e: NeedsSignInException) {
                needsSignIn = true
            } catch (e: Exception) {
                showMessage(e.userMessage())
            } finally {
                loadMutex.unlock()
            }
        }

        fun perform(acc: SteamAccount, targets: List<ConfirmationsApi.Confirmation>, accept: Boolean) {
            if (working || targets.isEmpty()) return
            scope.launch {
                working = true
                try {
                    ConfirmationsApi.respond(acc, targets, accept)
                    selected = selected - targets.map { it.id }.toSet()
                    load(acc)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: NeedsSignInException) {
                    needsSignIn = true
                } catch (e: Exception) {
                    showMessage(e.userMessage())
                } finally {
                    working = false
                }
            }
        }

        fun onCardAction(acc: SteamAccount, c: ConfirmationsApi.Confirmation, accept: Boolean) {
            if (isBatchable(c)) {
                perform(acc, listOf(c), accept)
            } else {
                pending = PendingAction(listOf(c), accept)
            }
        }

        fun batch(acc: SteamAccount, accept: Boolean) {
            val targets = confirmations.filter { it.id in selected && isBatchable(it) }
            perform(acc, targets, accept)
        }

        // Auto-refresh only while RESUMED; restarts (with an immediate load) on each resume.
        val lifecycleOwner = LocalLifecycleOwner.current
        LaunchedEffect(steamId, lifecycleOwner) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    load(account)
                    if (needsSignIn) break
                    delay(AUTO_REFRESH_MILLIS)
                }
            }
        }

        pending?.let { p ->
            AlertDialog(
                onDismissRequest = { pending = null },
                title = { Text("Account security") },
                text = {
                    Text("This is an account-security confirmation: ${p.items.first().headline}. Continue?")
                },
                confirmButton = {
                    TextButton(onClick = {
                        pending = null
                        perform(account, p.items, p.accept)
                    }) {
                        Text(if (p.accept) "Accept" else "Decline")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pending = null }) { Text("Cancel") }
                },
            )
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Confirmations") },
                    navigationIcon = {
                        TextButton(onClick = { nav.popBackStack() }) { Text("Back") }
                    },
                    actions = {
                        if (selected.isNotEmpty()) {
                            TextButton(
                                onClick = { batch(account, true) },
                                enabled = !working,
                            ) { Text("Accept selected") }
                            TextButton(
                                onClick = { batch(account, false) },
                                enabled = !working,
                            ) { Text("Decline selected") }
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { innerPadding ->
            PullToRefreshBox(
                isRefreshing = pullRefreshing,
                onRefresh = {
                    scope.launch {
                        pullRefreshing = true
                        try {
                            load(account)
                        } finally {
                            pullRefreshing = false
                        }
                    }
                },
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when {
                        needsSignIn -> item {
                            Box(
                                modifier = Modifier.fillParentMaxSize().padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Text(
                                        "Steam session expired. Sign in to see confirmations.",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Button(onClick = { nav.navigate("signin/$steamId") }) {
                                        Text("Sign in")
                                    }
                                }
                            }
                        }
                        loaded && confirmations.isEmpty() -> item {
                            Box(
                                modifier = Modifier.fillParentMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "No pending confirmations",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                        else -> items(confirmations, key = { it.id }) { c ->
                            ConfirmationCard(
                                c = c,
                                nowSeconds = nowSeconds,
                                checked = c.id in selected,
                                working = working,
                                onCheckedChange = { on ->
                                    selected = if (on) selected + c.id else selected - c.id
                                },
                                onAccept = { onCardAction(account, c, true) },
                                onDecline = { onCardAction(account, c, false) },
                                onOpen = { nav.navigate("confdetails/$steamId/${c.id}") },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmationCard(
    c: ConfirmationsApi.Confirmation,
    nowSeconds: Long,
    checked: Boolean,
    working: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onOpen: () -> Unit,
) {
    val batchable = isBatchable(c)
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Checkbox(
                    checked = checked && batchable,
                    onCheckedChange = onCheckedChange,
                    enabled = batchable && !working,
                )
                Spacer(Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = c.typeName.ifBlank { "Type ${c.type}" },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(c.headline, style = MaterialTheme.typography.titleMedium)
                    c.summary.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        relativeTime(c.creationTime, nowSeconds),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDecline, enabled = !working) { Text("Decline") }
                Button(onClick = onAccept, enabled = !working) { Text("Accept") }
            }
        }
    }
}

private fun relativeTime(epochSeconds: Long, nowSeconds: Long): String {
    if (epochSeconds <= 0L) return ""
    val diff = nowSeconds - epochSeconds
    return when {
        diff < 60 -> "just now"
        diff < 3_600 -> "${diff / 60} min ago"
        diff < 86_400 -> "${diff / 3_600} h ago"
        else -> "${diff / 86_400} d ago"
    }
}
