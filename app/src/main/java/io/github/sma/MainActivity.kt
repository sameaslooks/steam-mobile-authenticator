package io.github.sma

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.sma.background.NavExtras
import io.github.sma.background.BackgroundScheduler
import io.github.sma.background.Notifier
import io.github.sma.core.steam.SteamTime
import io.github.sma.data.AccountRepository
import io.github.sma.data.AppLock
import io.github.sma.ui.SmaApp
import io.github.sma.ui.SmaTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    // Route requested by a notification tap; consumed by SmaApp once unlocked.
    private val pendingRoute = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        takeRouteFrom(intent)

        AccountRepository.init(applicationContext)
        Notifier.ensureChannels(this)
        lifecycleScope.launch {
            AccountRepository.load()
            BackgroundScheduler.apply(applicationContext, AccountRepository.settings.value.notifications)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    SteamTime.sync()
                    delay(SYNC_INTERVAL_MS)
                }
            }
        }

        setContent {
            SmaTheme {
                // Surface provides background and onBackground content color, so text follows the theme.
                androidx.compose.material3.Surface(
                    modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                ) {
                    SmaApp(this, pendingRoute)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeRouteFrom(intent)
    }

    /** Reads and clears the route extra so a recreated activity does not navigate again. */
    private fun takeRouteFrom(intent: Intent) {
        val route = intent.getStringExtra(NavExtras.EXTRA_ROUTE) ?: return
        intent.removeExtra(NavExtras.EXTRA_ROUTE)
        pendingRoute.value = route
    }

    override fun onStart() {
        super.onStart()
        AppLock.onForeground()
    }

    override fun onStop() {
        AppLock.onBackground()
        super.onStop()
    }

    private companion object {
        const val SYNC_INTERVAL_MS = 15 * 60 * 1000L
    }
}
