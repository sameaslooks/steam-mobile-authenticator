package io.github.sma.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.github.sma.background.ConfirmationMonitor
import io.github.sma.core.mafile.SteamAccount
import io.github.sma.core.steam.ConfirmationsApi
import io.github.sma.core.steam.SteamTime
import io.github.sma.core.steam.userMessage
import io.github.sma.data.AccountOrder
import io.github.sma.data.AccountRepository
import io.github.sma.data.NicknameCache
import io.github.sma.data.SecureClipboard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TRANSFER_ROUTE = "transfer"

private val SORT_OPTIONS = listOf(
    AccountOrder.MANUAL to "Manual order",
    AccountOrder.LOGIN to "Login (A–Z)",
    AccountOrder.NICKNAME to "Nickname (A–Z)",
    AccountOrder.ADDED to "Order added",
    AccountOrder.PENDING_FIRST to "Pending first",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarState = remember { SnackbarHostState() }
    val accounts by AccountRepository.accounts.collectAsState()
    val loadError by AccountRepository.loadError.collectAsState()
    val pending by ConfirmationMonitor.pending.collectAsState()
    val loginRequests by ConfirmationMonitor.loginRequests.collectAsState()
    val monitorError by ConfirmationMonitor.lastError.collectAsState()
    val nicknames by NicknameCache.names.collectAsState()
    val sortMode by AccountOrder.sortMode.collectAsState()
    val savedOrder by AccountOrder.manualOrder.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var editingOrder by remember { mutableStateOf(false) }
    var sortDialogOpen by remember { mutableStateOf(false) }

    AccountOrder.load(context)
    NicknameCache.ensureLoaded(context)

    var timeProblem by remember { mutableStateOf(SteamTime.lastError != null || SteamTime.isStale()) }
    LaunchedEffect(Unit) {
        while (true) {
            timeProblem = SteamTime.lastError != null || SteamTime.isStale()
            delay(1_000L)
        }
    }

    // Refresh pending confirmations once on open; periodic polling is handled globally.
    LaunchedEffect(Unit) {
        ConfirmationMonitor.refresh(context, notify = false)
    }

    // Show each error once: clear it after the snackbar has been shown so it does not reappear on return.
    LaunchedEffect(monitorError) {
        monitorError?.let {
            snackbarState.showSnackbar(it)
            ConfirmationMonitor.clearError()
        }
    }

    val onCopy: (String) -> Unit = { code ->
        SecureClipboard.copy(context, "Steam Guard code", code)
        scope.launch { snackbarState.showSnackbar("Code copied") }
    }

    val onRefresh: () -> Unit = {
        scope.launch {
            refreshing = true
            try {
                ConfirmationMonitor.refresh(context, notify = false)
            } finally {
                refreshing = false
            }
        }
    }

    val onRespond: (String, String, Boolean) -> Unit = { steamId, confId, accept ->
        scope.launch {
            runCatching { ConfirmationMonitor.respond(context, steamId, listOf(confId), accept) }
                .onFailure { snackbarState.showSnackbar("Could not respond: ${it.userMessage()}") }
        }
    }

    fun accountOf(steamId: String): SteamAccount? =
        accounts.firstOrNull { it.steamId == steamId }

    val waitingConfirmations: List<Pair<String, ConfirmationsApi.Confirmation>> =
        pending.entries.flatMap { (steamId, list) -> list.map { steamId to it } }
    val waitingLogins = loginRequests.entries.filter { it.value > 0 }.toList()

    val visibleAccounts = remember(accounts, query, sortMode, savedOrder, nicknames, pending, loginRequests) {
        val needle = query.trim()
        val filtered = if (needle.isEmpty()) {
            accounts
        } else {
            accounts.filter { account ->
                account.accountName.contains(needle, ignoreCase = true) ||
                    nickOf(account, nicknames)?.contains(needle, ignoreCase = true) == true ||
                    account.steamId.contains(needle, ignoreCase = true)
            }
        }
        sortAccounts(filtered, sortMode, savedOrder, nicknames, pending, loginRequests)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    when {
                        editingOrder -> Text("Edit order")
                        searching -> TextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            placeholder = { Text("Search login, nickname or SteamID") },
                            trailingIcon = {
                                IconButton(onClick = {
                                    query = ""
                                    searching = false
                                }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Close search")
                                }
                            },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                        )
                        else -> Text("SMA")
                    }
                },
                actions = {
                    if (editingOrder) {
                        TextButton(onClick = { editingOrder = false }) {
                            Text("Done")
                        }
                    } else {
                        if (!searching) {
                            IconButton(onClick = { searching = true }) {
                                Icon(Icons.Filled.Search, contentDescription = "Search")
                            }
                        }
                        IconButton(onClick = { nav.navigate(Routes.ABOUT) }) {
                            Icon(Icons.Filled.Info, contentDescription = "About")
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Sort…") },
                                    onClick = {
                                        menuOpen = false
                                        sortDialogOpen = true
                                    },
                                )
                                if (sortMode == AccountOrder.MANUAL) {
                                    DropdownMenuItem(
                                        text = { Text("Edit order") },
                                        onClick = {
                                            menuOpen = false
                                            searching = false
                                            query = ""
                                            editingOrder = true
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Import .maFile") },
                                    onClick = {
                                        menuOpen = false
                                        nav.navigate(Routes.IMPORT)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Move from Steam app") },
                                    onClick = {
                                        menuOpen = false
                                        nav.navigate(TRANSFER_ROUTE)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Settings") },
                                    onClick = {
                                        menuOpen = false
                                        nav.navigate(Routes.SETTINGS)
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarState) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (timeProblem) {
                    item(key = "time-problem") {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Steam time not synced — codes may be wrong",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { scope.launch { SteamTime.sync() } }) {
                                    Text("Retry")
                                }
                            }
                        }
                    }
                }

                loadError?.let { message ->
                    item(key = "load-error") {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                if (waitingConfirmations.isNotEmpty() || waitingLogins.isNotEmpty()) {
                    item(key = "waiting-header") {
                        Text(
                            text = "Waiting for you",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    items(
                        waitingConfirmations,
                        key = { (steamId, conf) -> "conf-$steamId-${conf.id}" },
                    ) { (steamId, conf) ->
                        val owner = accountOf(steamId)
                        ConfirmationCard(
                            accountName = owner?.let { accountTitle(it) } ?: steamId,
                            conf = conf,
                            onRespond = { accept -> onRespond(steamId, conf.id, accept) },
                            onReview = { nav.navigate(Routes.confirmations(steamId)) },
                            onOpen = { nav.navigate("confdetails/$steamId/${conf.id}") },
                        )
                    }
                    items(
                        waitingLogins,
                        key = { entry -> "login-${entry.key}" },
                    ) { entry ->
                        val owner = accountOf(entry.key)
                        LoginRequestCard(
                            accountName = owner?.let { accountTitle(it) } ?: entry.key,
                            count = entry.value,
                            onOpen = { nav.navigate(Routes.loginRequests(entry.key)) },
                        )
                    }
                }

                if (accounts.isEmpty()) {
                    item(key = "empty") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = "No accounts. Import a .maFile to start.",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { nav.navigate(Routes.IMPORT) }) {
                                Text("Import .maFile")
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(onClick = { nav.navigate(TRANSFER_ROUTE) }) {
                                Text("Move from Steam app")
                            }
                        }
                    }
                } else if (visibleAccounts.isEmpty()) {
                    item(key = "no-match") {
                        Text(
                            text = "No accounts match",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 32.dp),
                        )
                    }
                } else {
                    itemsIndexed(visibleAccounts, key = { _, account -> account.steamId }) { index, account ->
                        AccountCard(
                            account = account,
                            onOpen = { nav.navigate(Routes.account(account.steamId)) },
                            onCopy = onCopy,
                            reordering = editingOrder,
                            canMoveUp = index > 0,
                            canMoveDown = index < visibleAccounts.lastIndex,
                            onMoveUp = {
                                AccountOrder.moveUp(context, account.steamId, visibleAccounts.map { it.steamId })
                            },
                            onMoveDown = {
                                AccountOrder.moveDown(context, account.steamId, visibleAccounts.map { it.steamId })
                            },
                        )
                    }
                }
            }
        }
    }

    if (sortDialogOpen) {
        AlertDialog(
            onDismissRequest = { sortDialogOpen = false },
            title = { Text("Sort accounts") },
            text = {
                Column {
                    SORT_OPTIONS.forEach { (mode, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = sortMode == mode,
                                    onClick = {
                                        AccountOrder.setMode(context, mode)
                                        sortDialogOpen = false
                                    },
                                    role = Role.RadioButton,
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = sortMode == mode, onClick = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { sortDialogOpen = false }) {
                    Text("Close")
                }
            },
        )
    }
}

/** Nickname from the cache, or null when unknown or blank. */
private fun nickOf(account: SteamAccount, nicknames: Map<String, String>): String? =
    nicknames[account.steamId]?.trim()?.takeIf { it.isNotEmpty() }

private fun isWaiting(
    steamId: String,
    pending: Map<String, List<ConfirmationsApi.Confirmation>>,
    loginRequests: Map<String, Int>,
): Boolean = pending[steamId].orEmpty().isNotEmpty() || (loginRequests[steamId] ?: 0) > 0

private fun sortAccounts(
    accounts: List<SteamAccount>,
    mode: String,
    savedOrder: List<String>,
    nicknames: Map<String, String>,
    pending: Map<String, List<ConfirmationsApi.Confirmation>>,
    loginRequests: Map<String, Int>,
): List<SteamAccount> = when (mode) {
    AccountOrder.MANUAL -> {
        val byId = accounts.associateBy { it.steamId }
        AccountOrder.manualOrder(accounts.map { it.steamId }, savedOrder).mapNotNull { byId[it] }
    }
    AccountOrder.LOGIN -> accounts.sortedBy { it.accountName.lowercase() }
    AccountOrder.NICKNAME -> accounts.sortedBy { (nickOf(it, nicknames) ?: it.accountName).lowercase() }
    AccountOrder.PENDING_FIRST -> accounts.sortedWith(
        compareBy<SteamAccount>(
            { if (isWaiting(it.steamId, pending, loginRequests)) 0 else 1 },
            { it.accountName.lowercase() },
        ),
    )
    else -> accounts
}

@Composable
private fun ConfirmationCard(
    accountName: String,
    conf: ConfirmationsApi.Confirmation,
    onRespond: (accept: Boolean) -> Unit,
    onReview: () -> Unit,
    onOpen: () -> Unit,
) {
    val tradeOrMarket = conf.type == 2 || conf.type == 3
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = accountName,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = typeLabel(conf.type),
                style = MaterialTheme.typography.labelMedium,
            )
            if (conf.headline.isNotBlank()) {
                Text(
                    text = conf.headline,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            conf.summary.firstOrNull()?.let { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                if (tradeOrMarket) {
                    TextButton(onClick = { onRespond(false) }) {
                        Text("Decline")
                    }
                    Button(onClick = { onRespond(true) }) {
                        Text("Accept")
                    }
                } else {
                    Button(onClick = onReview) {
                        Text("Review")
                    }
                }
            }
        }
    }
}

@Composable
private fun LoginRequestCard(
    accountName: String,
    count: Int,
    onOpen: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpen,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Sign-in request ($count) — $accountName",
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

private fun typeLabel(type: Int): String = when (type) {
    2 -> "Trade"
    3 -> "Market"
    else -> "Account security"
}

@Composable
private fun AccountCard(
    account: SteamAccount,
    onOpen: () -> Unit,
    onCopy: (String) -> Unit,
    reordering: Boolean = false,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpen,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = accountTitle(account),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                GuardCode(account = account, onCopy = onCopy)
            }
            if (reordering) {
                Column {
                    IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up")
                    }
                    IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down")
                    }
                }
            }
        }
    }
}
