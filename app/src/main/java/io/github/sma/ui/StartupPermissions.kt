package io.github.sma.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.github.sma.background.BackgroundScheduler
import io.github.sma.data.AccountRepository
import io.github.sma.data.NotificationSettings

private const val PREFS_NAME = "sma_prefs"
private const val KEY_BATTERY_PROMPT_SHOWN = "battery_prompt_shown"

/** Process-wide guard: the startup permission flow is decided once per app process. */
private var asked = false

/**
 * Startup permission flow, called once from the unlocked branch of [SmaApp].
 * 1. Android 13+: requests POST_NOTIFICATIONS when notifications are enabled in SMA and not granted,
 *    then re-applies the background scheduler.
 * 2. Service mode: offers to exempt SMA from battery optimisation (asked at most once per install).
 * Camera is not requested here; it is requested when the QR scanner opens.
 */
@Composable
fun StartupPermissions() {
    val ctx = LocalContext.current
    val settings by AccountRepository.settings.collectAsState()
    var showBattery by rememberSaveable { mutableStateOf(false) }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        BackgroundScheduler.apply(ctx, AccountRepository.settings.value.notifications)
        showBattery = batteryPromptDue(ctx, AccountRepository.settings.value.notifications.mode)
    }

    LaunchedEffect(Unit) {
        // The vault loads asynchronously and a fresh vault equals the default Settings (no new emission),
        // so wait for the load instead of keying on the settings value.
        while (!AccountRepository.isLoaded()) kotlinx.coroutines.delay(200)
        if (asked) return@LaunchedEffect
        asked = true
        @Suppress("NAME_SHADOWING") val settings = AccountRepository.settings.value
        val mode = settings.notifications.mode
        val needsNotificationPermission =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                mode != NotificationSettings.MODE_OFF &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            showBattery = batteryPromptDue(ctx, mode)
        }
    }

    if (showBattery) {
        LaunchedEffect(Unit) { markBatteryPromptShown(ctx) }
        AlertDialog(
            onDismissRequest = { showBattery = false },
            title = { Text("Keep alerts running") },
            text = {
                Text(
                    "Real-time alerts need SMA to run in the background. Allow it to ignore battery " +
                        "optimisation? On Xiaomi / HyperOS also enable Autostart for SMA.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showBattery = false
                    requestIgnoreBatteryOptimizations(ctx)
                }) {
                    Text("Allow")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBattery = false }) {
                    Text("Not now")
                }
            },
        )
    }
}

private fun batteryPromptDue(ctx: Context, mode: String): Boolean {
    if (mode != NotificationSettings.MODE_SERVICE) return false
    val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    if (prefs.getBoolean(KEY_BATTERY_PROMPT_SHOWN, false)) return false
    val powerManager = ctx.getSystemService(PowerManager::class.java) ?: return false
    return !powerManager.isIgnoringBatteryOptimizations(ctx.packageName)
}

private fun markBatteryPromptShown(ctx: Context) {
    ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_BATTERY_PROMPT_SHOWN, true)
        .apply()
}

private fun requestIgnoreBatteryOptimizations(ctx: Context) {
    val pkg = ctx.packageName
    try {
        ctx.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")),
        )
    } catch (e: ActivityNotFoundException) {
        ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}
