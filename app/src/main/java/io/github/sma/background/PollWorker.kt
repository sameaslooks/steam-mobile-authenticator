package io.github.sma.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

class PollWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        try {
            ConfirmationMonitor.refresh(applicationContext, notify = true, reportErrors = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Background poll: failures are not reported to the UI; the work itself always succeeds.
        }
        return Result.success()
    }
}
