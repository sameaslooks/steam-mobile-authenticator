package io.github.sma.background

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import io.github.sma.core.steam.userMessage
import io.github.sma.data.AccountRepository
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Foreground service that polls Steam for confirmations while the user opted into "service" mode. */
class PollService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifier.ensureChannels(this)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifier.SERVICE_NOTIFICATION_ID, Notifier.serviceNotification(this), type)
        if (loop?.isActive != true) {
            loop = scope.launch { runLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runLoop() {
        AccountRepository.init(applicationContext)
        var failures = 0
        while (true) {
            AccountRepository.load()
            val settings = AccountRepository.settings.value.notifications
            var intervalMs = settings.serviceSeconds.coerceAtLeast(MIN_INTERVAL_SECONDS) * 1000L
            val power = getSystemService(PowerManager::class.java)
            if (settings.slowWhenScreenOff && power != null && !power.isInteractive) intervalMs *= 2

            val waitMs: Long = if (!networkValidated()) {
                Notifier.updateServiceNotification(this, "Waiting for network (${nowHhMm()})")
                intervalMs
            } else if (checkConfirmations()) {
                failures = 0
                Notifier.updateServiceNotification(this, "Last check ${nowHhMm()} — OK")
                intervalMs
            } else {
                failures++
                val reason = ConfirmationMonitor.lastFailureMessage ?: "failed"
                Notifier.updateServiceNotification(this, "Last check ${nowHhMm()} — $reason")
                if (failures >= MAX_FAILURES) {
                    pauseAndStop()
                    return
                }
                backoffMs(failures)
            }
            delay(waitMs)
        }
    }

    private suspend fun checkConfirmations(): Boolean =
        try {
            ConfirmationMonitor.refresh(applicationContext, notify = true, reportErrors = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ConfirmationMonitor.lastFailureMessage = e.userMessage()
            false
        }

    private fun nowHhMm(): String = LocalTime.now().format(TIME_FORMAT)

    private fun networkValidated(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun pauseAndStop() {
        Notifier.postStatus(
            this,
            PAUSED_ID,
            "Confirmation checks paused",
            "Open SMA to resume checks",
            "settings",
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun backoffMs(failures: Int): Long =
        (BACKOFF_BASE_MS shl (failures - 1)).coerceAtMost(BACKOFF_MAX_MS)

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        const val MIN_INTERVAL_SECONDS = 10
        const val MAX_FAILURES = 10
        const val BACKOFF_BASE_MS = 30_000L
        const val BACKOFF_MAX_MS = 15 * 60 * 1000L
        val PAUSED_ID = "service-paused".hashCode()
    }
}
