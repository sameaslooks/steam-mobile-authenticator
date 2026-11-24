package io.github.sma.background

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.github.sma.data.NotificationSettings
import java.util.concurrent.TimeUnit
import kotlin.math.max

/** Applies the notification mode: WorkManager periodic polling, the foreground PollService, or nothing. */
object BackgroundScheduler {
    private const val WORK_NAME = "sma_poll"
    private const val MIN_PERIOD_MINUTES = 15L
    private val START_FAILED_ID = "service-start-failed".hashCode()

    fun apply(ctx: Context, s: NotificationSettings) {
        val app = ctx.applicationContext
        Notifier.ensureChannels(app)
        val workManager = WorkManager.getInstance(app)
        when (s.mode) {
            NotificationSettings.MODE_PERIODIC -> {
                stopPollService(app)
                val request = PeriodicWorkRequestBuilder<PollWorker>(
                    max(MIN_PERIOD_MINUTES, s.periodicMinutes.toLong()),
                    TimeUnit.MINUTES,
                )
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
                workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
            }

            NotificationSettings.MODE_SERVICE -> {
                workManager.cancelUniqueWork(WORK_NAME)
                try {
                    ContextCompat.startForegroundService(app, Intent(app, PollService::class.java))
                } catch (e: IllegalStateException) {
                    // ForegroundServiceStartNotAllowedException (API 31+) extends IllegalStateException.
                    Notifier.postStatus(
                        app,
                        START_FAILED_ID,
                        "Open SMA to resume checks",
                        "Background checks could not start in the background",
                        null,
                    )
                }
            }

            else -> {
                workManager.cancelUniqueWork(WORK_NAME)
                stopPollService(app)
            }
        }
    }

    private fun stopPollService(app: Context) {
        app.stopService(Intent(app, PollService::class.java))
    }
}
