package io.github.sma.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.github.sma.core.crypto.Jwt
import io.github.sma.core.mafile.MaFile
import io.github.sma.core.steam.SessionManager
import io.github.sma.core.steam.SteamTime
import io.github.sma.core.steam.userMessage
import io.github.sma.data.AccountRepository
import io.github.sma.data.AppLock
import io.github.sma.data.NicknameCache
import io.github.sma.data.SecureClipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(steamId: String, nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarState = remember { SnackbarHostState() }
    val accounts by AccountRepository.accounts.collectAsState()
    val account = accounts.firstOrNull { it.steamId == steamId }
    val activity = context.findFragmentActivity()

    LaunchedEffect(steamId) {
        NicknameCache.refresh(context, steamId, force = true)
    }

    var showRevocation by remember { mutableStateOf(false) }
    var showRemove by remember { mutableStateOf(false) }
    var backupExpanded by rememberSaveable { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream") /* json MIME makes pickers append .json */,
    ) { uri ->
        if (uri != null && account != null) {
            scope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    runCatching {
                        val out = context.contentResolver.openOutputStream(uri)
                            ?: error("Cannot open the selected file")
                        out.use { it.write(MaFile.export(account).toByteArray(Charsets.UTF_8)) }
                    }
                }
                val message = if (outcome.isSuccess) {
                    "Exported .maFile"
                } else {
                    "Export failed: ${outcome.exceptionOrNull()?.userMessage() ?: "unknown error"}"
                }
                snackbarState.showSnackbar(message)
            }
        }
    }

    if (account == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            Text("Account not found", style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { nav.popBackStack() }) {
                Text("Back")
            }
        }
        return
    }

    /** Runs [action] only after a successful AppLock prompt. */
    val requireAuth: (String, () -> Unit) -> Unit = { title, action ->
        if (activity == null) {
            scope.launch { snackbarState.showSnackbar("Authentication is unavailable") }
        } else {
            AppLock.prompt(activity, title) { ok -> if (ok) action() }
        }
    }

    val accountLabel = accountTitle(account)
    val signedIn = account.refreshToken?.let { Jwt.isUsableMobile(it, SteamTime.now()) } == true

    if (showRevocation) {
        AlertDialog(
            onDismissRequest = { showRevocation = false },
            title = { Text("Revocation code") },
            text = {
                Column {
                    Text(
                        text = account.revocationCode ?: "Not available",
                        style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Store this code somewhere safe. Anyone who has it can remove this authenticator from your Steam account.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showRevocation = false }) {
                    Text("Close")
                }
            },
        )
    }

    if (showRemove) {
        AlertDialog(
            onDismissRequest = { showRemove = false },
            title = { Text("Remove account?") },
            text = {
                Column {
                    Text(
                        text = "Revocation code: ${account.revocationCode ?: "Not available"}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "This does NOT remove the authenticator from Steam. Make sure you have a backup.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showRemove = false
                    scope.launch {
                        AccountRepository.remove(steamId)
                        SessionManager.invalidate(steamId)
                        nav.popBackStack()
                    }
                }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemove = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(accountLabel) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "SteamID: ${account.steamId}",
                style = MaterialTheme.typography.bodyMedium,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    GuardCode(
                        account = account,
                        onCopy = { code ->
                            SecureClipboard.copy(context, "Steam Guard code", code)
                            scope.launch { snackbarState.showSnackbar("Code copied") }
                        },
                    )
                }
            }

            if (!signedIn) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Sign in to see confirmations and sign-in requests")
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { nav.navigate(Routes.signIn(steamId)) }) {
                            Text("Sign in")
                        }
                    }
                }
            }

            Button(
                onClick = { nav.navigate(Routes.confirmations(steamId)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Confirmations") }

            OutlinedButton(
                onClick = { nav.navigate(Routes.qr(steamId)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Scan QR login") }

            OutlinedButton(
                onClick = {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://steamcommunity.com/profiles/$steamId/"),
                    )
                    try {
                        context.startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        scope.launch { snackbarState.showSnackbar("No app or browser can open the profile") }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open Steam profile") }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { backupExpanded = !backupExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Backup", style = MaterialTheme.typography.titleMedium)
                Icon(
                    imageVector = if (backupExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (backupExpanded) "Collapse backup" else "Expand backup",
                )
            }

            AnimatedVisibility(visible = backupExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            requireAuth("Export .maFile") {
                                exportLauncher.launch("$steamId.maFile")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Export .maFile") }

                    OutlinedButton(
                        onClick = {
                            requireAuth("Copy otpauth URI") {
                                runCatching { MaFile.otpauth(account) }
                                    .onSuccess { uri ->
                                        SecureClipboard.copy(context, "otpauth URI", uri)
                                        scope.launch { snackbarState.showSnackbar("otpauth URI copied") }
                                    }
                                    .onFailure {
                                        scope.launch { snackbarState.showSnackbar("Cannot build otpauth URI") }
                                    }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Copy otpauth URI (password managers, e.g. KeePassXC)") }

                    OutlinedButton(
                        onClick = { requireAuth("Show revocation code") { showRevocation = true } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Show revocation code") }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = { showRemove = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Remove account", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
