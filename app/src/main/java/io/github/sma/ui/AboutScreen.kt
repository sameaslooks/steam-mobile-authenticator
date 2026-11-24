package io.github.sma.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.github.sma.R
import io.github.sma.core.UpdateChecker
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(nav: NavController) {
    val context = LocalContext.current
    val repoUrl = stringResource(R.string.repo_url)
    val authorUrl = stringResource(R.string.author_url)
    val versionName = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "unknown"
    }
    val certFingerprint = remember(context) { signingCertSha256(context) ?: "unavailable" }
    val scope = rememberCoroutineScope()
    val lastCheckMs by UpdateChecker.lastCheckMs.collectAsState()
    var checking by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "SMA — Steam Mobile Authenticator",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "An open-source Steam Guard authenticator for Android. Generates login codes, lets you review and confirm trades and market listings, and approve sign-ins by QR code or notification. Secrets stay encrypted on your device — no servers, no telemetry.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Column {
                Text("Version $versionName", style = MaterialTheme.typography.bodyMedium)
                Text("Package ${context.packageName}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "by Sergei K. · @sameaslooks",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { openUrl(context, authorUrl) },
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { openUrl(context, repoUrl) }
                    .padding(vertical = 4.dp),
            ) {
                Text("Source code", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = repoUrl,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Column {
                Text("Updates", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = if (lastCheckMs == 0L) {
                        "Last checked: never"
                    } else {
                        "Last checked: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(Date(lastCheckMs))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(
                    enabled = !checking,
                    onClick = {
                        checking = true
                        scope.launch {
                            val message = when (val result = UpdateChecker.checkNow(context)) {
                                is UpdateChecker.CheckResult.UpToDate -> "You have the latest version"
                                is UpdateChecker.CheckResult.Available ->
                                    "Version ${result.update.tag.trimStart('v', 'V')} is available"
                                is UpdateChecker.CheckResult.Failed ->
                                    "Could not check for updates: ${result.message}"
                            }
                            checking = false
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    },
                ) {
                    Text("Check now")
                }
            }
            OutlinedButton(onClick = { nav.navigate(Routes.LICENSES) }) {
                Text("Open-source licenses")
            }
            Column {
                Text("Signing certificate SHA-256", style = MaterialTheme.typography.labelMedium)
                SelectionContainer {
                    Text(
                        text = certFingerprint,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
                Text(
                    text = "Shown for reference only: a modified APK can display any value here. To verify an APK, check it with apksigner before installing.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = "Not affiliated with Valve Corporation. Steam is a trademark of Valve.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

internal fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (ignored: ActivityNotFoundException) {
        // No app can handle the link; nothing to open.
    }
}

private fun signingCertSha256(context: Context): String? = runCatching {
    val pm = context.packageManager
    val pkg = context.packageName
    val signature: Signature? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val signingInfo = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
        if (signingInfo == null) {
            null
        } else if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners?.firstOrNull()
        } else {
            signingInfo.signingCertificateHistory?.firstOrNull()
        }
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
    }
    signature?.let { sha256Hex(it.toByteArray()) }
}.getOrNull()

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(":") { "%02x".format(it.toInt() and 0xff) }
