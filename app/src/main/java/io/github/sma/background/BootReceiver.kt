package io.github.sma.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.sma.data.AccountRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val app = context.applicationContext
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                AccountRepository.init(app)
                AccountRepository.load()
                BackgroundScheduler.apply(app, AccountRepository.settings.value.notifications)
            } catch (e: Exception) {
                // Nothing else to do: the scheduler is applied again when the app is opened.
            } finally {
                result.finish()
            }
        }
    }
}
