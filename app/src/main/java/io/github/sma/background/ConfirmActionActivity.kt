package io.github.sma.background

import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import io.github.sma.data.AppLock
import kotlinx.coroutines.launch

/** Headless activity opened from a confirmation notification. Requires device unlock before acting. */
class ConfirmActionActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val steamId = intent.getStringExtra(EXTRA_STEAM_ID)
        val confId = intent.getStringExtra(EXTRA_CONF_ID)
        val accept = intent.getBooleanExtra(EXTRA_ACCEPT, false)
        if (steamId == null || confId == null) {
            finish()
            return
        }

        val title = if (accept) "Accept confirmation" else "Decline confirmation"
        AppLock.unlock(this, title) { ok ->
            if (!ok) {
                finish()
                return@unlock
            }
            lifecycleScope.launch {
                try {
                    ConfirmationMonitor.respond(applicationContext, steamId, listOf(confId), accept)
                    toast(if (accept) "Accepted" else "Declined")
                } catch (e: Exception) {
                    toast(if (accept) "Could not accept confirmation" else "Could not decline confirmation")
                } finally {
                    finish()
                }
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_STEAM_ID = "steamId"
        const val EXTRA_CONF_ID = "confId"
        const val EXTRA_ACCEPT = "accept"
    }
}
