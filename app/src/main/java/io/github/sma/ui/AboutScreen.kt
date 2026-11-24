package io.github.sma.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.github.sma.R
import java.security.MessageDigest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(nav: NavController) {
    val context = LocalContext.current
    val repoUrl = stringResource(R.string.repo_url)
    val versionName = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "unknown"
    }
    val certFingerprint = remember(context) { signingCertSha256(context) ?: "unavailable" }

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
            Column {
                Text("Version $versionName", style = MaterialTheme.typography.bodyMedium)
                Text("Package ${context.packageName}", style = MaterialTheme.typography.bodyMedium)
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
                text = "Map data © OpenStreetMap contributors (ODbL)",
                style = MaterialTheme.typography.bodySmall,
            )
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
