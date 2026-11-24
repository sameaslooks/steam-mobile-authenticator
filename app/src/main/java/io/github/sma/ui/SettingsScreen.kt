package io.github.sma.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import io.github.sma.background.BackgroundScheduler
import io.github.sma.core.steam.userMessage
import io.github.sma.data.AccountRepository
import io.github.sma.data.NotificationSettings
import kotlinx.coroutines.launch

private typealias NotificationChange = (NotificationSettings) -> NotificationSettings

private val PERIODIC_MINUTES = listOf(15, 30, 60)
private val SERVICE_SECONDS = listOf(30 to "30 s", 60 to "1 min", 120 to "2 min", 300 to "5 min")

private fun serviceBatteryNote(seconds: Int): String = when (seconds) {
    30 -> "≈ 3–6 %/day"
    60 -> "≈ 2–4 %/day"
    else -> "≈ 1–2 %/day"
}

private fun notificationPermissionMissing(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by AccountRepository.settings.collectAsState()
    val accounts by AccountRepository.accounts.collectAsState()
    val notif = settings.notifications

    // Change waiting for the POST_NOTIFICATIONS answer; applied whether or not the user allowed it.
    var pendingChange by remember { mutableStateOf<NotificationChange?>(null) }

    fun applyChange(change: NotificationChange) {
        scope.launch {
            try {
                AccountRepository.updateSettings { s -> s.copy(notifications = change(s.notifications)) }
                BackgroundScheduler.apply(context, AccountRepository.settings.value.notifications)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Could not save settings: ${e.userMessage()}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    fun setShowSignInMap(on: Boolean) {
        scope.launch {
            try {
                AccountRepository.updateSettings { it.copy(showSignInMap = on) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Could not save settings: ${e.userMessage()}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        pendingChange?.let { applyChange(it) }
        pendingChange = null
    }

    fun requestChange(change: NotificationChange) {
        val next = change(notif)
        val enablesMode = next.mode != notif.mode && next.mode != NotificationSettings.MODE_OFF
        if (enablesMode && notificationPermissionMissing(context)) {
            pendingChange = change
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            applyChange(change)
        }
    }

    val notificationsBlocked = !NotificationManagerCompat.from(context).areNotificationsEnabled()
    val powerManager = context.getSystemService(PowerManager::class.java)
    val ignoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(context.packageName)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Confirmation alerts",
                style = MaterialTheme.typography.titleMedium,
            )

            ModeOption(
                title = "Off",
                note = "No background checks",
                selected = notif.mode == NotificationSettings.MODE_OFF,
                onSelect = { requestChange { it.copy(mode = NotificationSettings.MODE_OFF) } },
            )

            ModeOption(
                title = "Every N minutes (in the background, battery friendly)",
                note = "≈ under 0.5 % battery/day, alerts can be delayed by Android",
                selected = notif.mode == NotificationSettings.MODE_PERIODIC,
                onSelect = { requestChange { it.copy(mode = NotificationSettings.MODE_PERIODIC) } },
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PERIODIC_MINUTES.forEach { minutes ->
                        FilterChip(
                            selected = notif.periodicMinutes == minutes,
                            onClick = { requestChange { it.copy(periodicMinutes = minutes) } },
                            label = { Text("$minutes min") },
                        )
                    }
                }
            }

            ModeOption(
                title = "Real-time service (permanent notification)",
                note = "Checks continuously. Battery use depends on the interval",
                selected = notif.mode == NotificationSettings.MODE_SERVICE,
                onSelect = { requestChange { it.copy(mode = NotificationSettings.MODE_SERVICE) } },
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SERVICE_SECONDS.forEach { (seconds, label) ->
                        FilterChip(
                            selected = notif.serviceSeconds == seconds,
                            onClick = { requestChange { it.copy(serviceSeconds = seconds) } },
                            label = { Text(label) },
                        )
                    }
                }
                Text(
                    text = "Battery: ${serviceBatteryNote(notif.serviceSeconds)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Check less often while the screen is off",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = notif.slowWhenScreenOff,
                        onCheckedChange = { on -> requestChange { it.copy(slowWhenScreenOff = on) } },
                    )
                }
            }

            if (notif.mode != NotificationSettings.MODE_OFF && notificationsBlocked) {
                Text(
                    text = "Notifications are turned off for SMA, so alerts will not be shown.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                }) {
                    Text("Open notification settings")
                }
            }

            if (accounts.isNotEmpty()) {
                Text(
                    text = "Accounts",
                    style = MaterialTheme.typography.titleMedium,
                )
                accounts.forEach { account ->
                    val steamId = account.steamId
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Alerts for ${account.accountName}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = steamId !in notif.disabledAccounts,
                            onCheckedChange = { on ->
                                requestChange { n ->
                                    n.copy(
                                        disabledAccounts = if (on) {
                                            n.disabledAccounts - steamId
                                        } else {
                                            n.disabledAccounts + steamId
                                        },
                                    )
                                }
                            },
                        )
                    }
                }
            }

            Text(
                text = "Sign-in requests",
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Show map",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "Shows where a sign-in comes from on an OpenStreetMap map.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.showSignInMap,
                    onCheckedChange = { on -> setShowSignInMap(on) },
                )
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Keep alerts working",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (!ignoringBatteryOptimizations) {
                        Button(onClick = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }) {
                            Text("Allow running in background")
                        }
                    }
                    Text(
                        text = "Xiaomi / HyperOS: also enable Autostart for SMA in Security app → Permissions → Autostart.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    }) {
                        Text("Open app info")
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeOption(
    title: String,
    note: String,
    selected: Boolean,
    onSelect: () -> Unit,
    details: @Composable () -> Unit = {},
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // onClick = null drops the 48dp touch box, so the radio is ~20dp: nudge it to the title's 24dp line.
            RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(top = 2.dp))
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Column(
                modifier = Modifier.padding(start = 48.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                details()
            }
        }
    }
}
