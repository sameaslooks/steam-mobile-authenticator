package io.github.sma.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.github.sma.core.mafile.Manifest
import io.github.sma.core.mafile.MaFile
import io.github.sma.core.mafile.SteamAccount
import io.github.sma.core.steam.userMessage
import io.github.sma.data.AccountRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ImportResult(val fileName: String, val message: String, val isError: Boolean)

private data class EncryptedFile(val fileName: String, val text: String)

private data class PendingReplace(val fileName: String, val account: SteamAccount)

private fun displayName(context: Context, uri: Uri): String {
    val fromProvider = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()
    return fromProvider ?: uri.lastPathSegment ?: "file"
}

private fun readText(context: Context, uri: Uri): String {
    val stream = context.contentResolver.openInputStream(uri)
        ?: throw IllegalStateException("Cannot open the selected file")
    return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
}

/** SDA-encrypted .maFile files are base64 blobs, not JSON objects. */
private fun isEncrypted(text: String): Boolean = !text.trim().startsWith("{")

private fun decryptOne(manifest: Manifest, file: EncryptedFile, password: String): SteamAccount {
    val entry = manifest.entries.firstOrNull { it.filename == file.fileName }
        ?: error("No manifest entry for ${file.fileName}")
    val salt = entry.salt ?: error("Manifest entry has no salt")
    val iv = entry.iv ?: error("Manifest entry has no iv")
    val plain = MaFile.decryptSda(file.text.trim(), password, salt, iv)
    return MaFile.parse(plain, file.fileName)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val results = remember { mutableStateListOf<ImportResult>() }
    val encrypted = remember { mutableStateListOf<EncryptedFile>() }
    val replaceQueue = remember { mutableStateListOf<PendingReplace>() }
    var manifest by remember { mutableStateOf<Manifest?>(null) }
    var manifestName by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    /** Adds the account, or queues a Replace? decision if its steamId already exists. */
    val acceptAccount: (String, SteamAccount) -> Unit = { fileName, account ->
        if (AccountRepository.find(account.steamId) != null) {
            replaceQueue.add(PendingReplace(fileName, account))
        } else {
            scope.launch {
                AccountRepository.upsert(account)
                results.add(ImportResult(fileName, "Added ${account.accountName} (${account.steamId})", isError = false))
            }
        }
    }

    val handleText: (String, String) -> Unit = { fileName, text ->
        if (isEncrypted(text)) {
            encrypted.add(EncryptedFile(fileName, text))
        } else {
            runCatching { MaFile.parse(text, fileName) }
                .onSuccess { account -> acceptAccount(fileName, account) }
                .onFailure { e ->
                    results.add(ImportResult(fileName, e.userMessage(), isError = true))
                }
        }
    }

    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                busy = true
                val loaded: List<Pair<String, Result<String>>> = withContext(Dispatchers.IO) {
                    uris.map { uri ->
                        displayName(context, uri) to runCatching { readText(context, uri) }
                    }
                }
                loaded.forEach { (fileName, read) ->
                    read.fold(
                        onSuccess = { text -> handleText(fileName, text) },
                        onFailure = { e ->
                            results.add(ImportResult(fileName, e.userMessage(), isError = true))
                        },
                    )
                }
                busy = false
            }
        }
    }

    val pickManifest = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                val loaded = withContext(Dispatchers.IO) {
                    runCatching {
                        displayName(context, uri) to MaFile.parseManifest(readText(context, uri))
                    }
                }
                loaded
                    .onSuccess { (name, parsed) ->
                        manifest = parsed
                        manifestName = name
                    }
                    .onFailure { e ->
                        results.add(ImportResult("manifest.json", e.userMessage(), isError = true))
                    }
                busy = false
            }
        }
    }

    val decryptPending: () -> Unit = {
        val currentManifest = manifest
        val pw = password
        if (currentManifest != null && pw.isNotEmpty()) {
            scope.launch {
                busy = true
                val batch = encrypted.toList()
                val outcomes = withContext(Dispatchers.IO) {
                    batch.map { file -> file to runCatching { decryptOne(currentManifest, file, pw) } }
                }
                outcomes.forEach { (file, outcome) ->
                    outcome.fold(
                        onSuccess = { account ->
                            // Remove only the successfully decrypted file; failed ones stay for a retry.
                            encrypted.remove(file)
                            acceptAccount(file.fileName, account)
                        },
                        onFailure = { e ->
                            results.add(
                                ImportResult(file.fileName, e.userMessage(), isError = true),
                            )
                        },
                    )
                }
                password = ""
                busy = false
            }
        }
    }

    replaceQueue.firstOrNull()?.let { pending ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Replace account?") },
            text = {
                Text(
                    "${pending.account.accountName} (${pending.account.steamId}) already exists. " +
                        "Replace it with ${pending.fileName}?",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    replaceQueue.removeAt(0)
                    scope.launch {
                        AccountRepository.upsert(pending.account)
                        results.add(
                            ImportResult(pending.fileName, "Replaced ${pending.account.accountName} (${pending.account.steamId})", isError = false),
                        )
                    }
                }) {
                    Text("Replace")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    replaceQueue.removeAt(0)
                    results.add(ImportResult(pending.fileName, "Skipped, account already exists", isError = false))
                }) {
                    Text("Skip")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = { pickFiles.launch(arrayOf("*/*")) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Choose .maFile(s)")
            }

            if (busy) {
                CircularProgressIndicator()
            }

            results.forEach { result ->
                Text(
                    text = "${result.fileName}: ${result.message}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (result.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }

            if (encrypted.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Encrypted files: ${encrypted.joinToString { it.fileName }}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = "These files are encrypted. Pick the manifest.json from the same export folder and enter the encryption password.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(
                            onClick = { pickManifest.launch(arrayOf("*/*")) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(manifestName?.let { "Manifest: $it" } ?: "Choose manifest.json")
                        }
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Encryption password") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = decryptPending,
                            enabled = !busy && manifest != null && password.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Decrypt")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Files are read once; the copy you pick stays where it is — delete it if you no longer need it.",
                style = MaterialTheme.typography.bodySmall,
            )
            // "Done" appears only after at least one account was actually added or replaced.
            if (results.any { !it.isError && (it.message.startsWith("Added") || it.message.startsWith("Replaced")) }) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { nav.popBackStack() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Done")
                }
            }
        }
    }
}
