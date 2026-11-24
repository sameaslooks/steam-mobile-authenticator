package io.github.sma.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

private const val APACHE_LICENSE_ASSET = "licenses/APACHE-2.0.txt"
private const val ZXING_NOTICE_ASSET = "licenses/ZXING-NOTICE.txt"

/**
 * One third-party component. Apache 2.0 entries expand inline to show the bundled license text;
 * [opensUrlOnTap] entries (ODbL) open [projectUrl] externally instead.
 */
private data class ThirdPartyLibrary(
    val name: String,
    val copyright: String,
    val license: String,
    val projectUrl: String,
    val showsNotice: Boolean = false,
    val opensUrlOnTap: Boolean = false,
)

private val thirdPartyLibraries = listOf(
    ThirdPartyLibrary(
        name = "AndroidX (Core, Activity, Lifecycle, Navigation, Fragment, Biometric, Camera, WebKit, Work)",
        copyright = "The Android Open Source Project",
        license = "Apache License 2.0",
        projectUrl = "https://developer.android.com/jetpack/androidx",
    ),
    ThirdPartyLibrary(
        name = "Jetpack Compose and Material 3",
        copyright = "The Android Open Source Project",
        license = "Apache License 2.0",
        projectUrl = "https://developer.android.com/jetpack/compose",
    ),
    ThirdPartyLibrary(
        name = "Kotlin standard library",
        copyright = "JetBrains s.r.o. and Kotlin Programming Language contributors",
        license = "Apache License 2.0",
        projectUrl = "https://github.com/JetBrains/kotlin",
    ),
    ThirdPartyLibrary(
        name = "kotlinx.coroutines",
        copyright = "JetBrains s.r.o. and contributors",
        license = "Apache License 2.0",
        projectUrl = "https://github.com/Kotlin/kotlinx.coroutines",
    ),
    ThirdPartyLibrary(
        name = "kotlinx.serialization",
        copyright = "JetBrains s.r.o. and contributors",
        license = "Apache License 2.0",
        projectUrl = "https://github.com/Kotlin/kotlinx.serialization",
    ),
    ThirdPartyLibrary(
        name = "OkHttp and Okio",
        copyright = "Square, Inc.",
        license = "Apache License 2.0",
        projectUrl = "https://github.com/square/okhttp",
    ),
    ThirdPartyLibrary(
        name = "ZXing",
        copyright = "ZXing authors",
        license = "Apache License 2.0 (with NOTICE)",
        projectUrl = "https://github.com/zxing/zxing",
        showsNotice = true,
    ),
    ThirdPartyLibrary(
        name = "osmdroid",
        copyright = "osmdroid contributors",
        license = "Apache License 2.0",
        projectUrl = "https://github.com/osmdroid/osmdroid",
    ),
    ThirdPartyLibrary(
        name = "OpenStreetMap map data",
        copyright = "© OpenStreetMap contributors",
        license = "Open Database License (ODbL)",
        projectUrl = "https://www.openstreetmap.org/copyright",
        opensUrlOnTap = true,
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(nav: NavController) {
    val context = LocalContext.current
    val apacheText = remember(context) { readAsset(context, APACHE_LICENSE_ASSET) }
    val zxingNotice = remember(context) { readAsset(context, ZXING_NOTICE_ASSET) }
    val openProject: (String) -> Unit = { url -> openUrl(context, url) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Open-source licenses") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = "SMA is licensed under the MIT License. It includes the following third-party components.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            items(thirdPartyLibraries, key = { it.name }) { library ->
                LibraryItem(
                    library = library,
                    expanded = expanded == library.name,
                    licenseText = apacheText,
                    noticeText = zxingNotice,
                    onToggle = { expanded = if (expanded == library.name) null else library.name },
                    onOpenUrl = openProject,
                )
            }
        }
    }
}

@Composable
private fun LibraryItem(
    library: ThirdPartyLibrary,
    expanded: Boolean,
    licenseText: String,
    noticeText: String,
    onToggle: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val monospace = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (library.opensUrlOnTap) onOpenUrl(library.projectUrl) else onToggle()
                }
                .padding(vertical = 4.dp),
        ) {
            Text(library.name, style = MaterialTheme.typography.titleSmall)
            Text(library.copyright, style = MaterialTheme.typography.bodySmall)
            Text(library.license, style = MaterialTheme.typography.bodySmall)
            if (!library.opensUrlOnTap) {
                Text(
                    text = if (expanded) "Hide license text" else "Show license text",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (expanded && !library.opensUrlOnTap) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Column {
                    Text(text = licenseText, style = monospace)
                    if (library.showsNotice) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("NOTICE", style = MaterialTheme.typography.labelMedium)
                        Text(text = noticeText, style = monospace)
                    }
                }
            }
        }
        TextButton(onClick = { onOpenUrl(library.projectUrl) }) {
            Text("Project page")
        }
    }
}

private fun readAsset(context: Context, path: String): String =
    runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }
        .getOrElse { "Could not load $path" }
