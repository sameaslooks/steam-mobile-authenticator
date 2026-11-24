package io.github.sma.data

import android.content.Context
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AppLock {
    private const val AUTO_LOCK_MS = 60_000L

    private val AUTHENTICATORS: Int =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    // elapsedRealtime timestamp of last onBackground(); -1 when not backgrounded. Main thread only.
    private var backgroundedAt: Long = -1L

    fun lock() {
        _unlocked.value = false
    }

    fun canAuthenticate(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Shows the system biometric / device credential prompt.
     * onResult(false) when no screen lock is set up (caller should tell the user to set one up)
     * or when authentication fails or is cancelled.
     */
    fun prompt(activity: FragmentActivity, title: String, onResult: (Boolean) -> Unit) {
        if (!canAuthenticate(activity)) {
            onResult(false)
            return
        }
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onResult(true)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onResult(false)
            }
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        BiometricPrompt(activity, executor, callback).authenticate(info)
    }

    fun unlock(activity: FragmentActivity, title: String, onResult: (Boolean) -> Unit = {}) {
        prompt(activity, title) { ok ->
            if (ok) _unlocked.value = true
            onResult(ok)
        }
    }

    fun onBackground() {
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    fun onForeground() {
        val at = backgroundedAt
        if (at < 0) return
        backgroundedAt = -1L
        if (SystemClock.elapsedRealtime() - at > AUTO_LOCK_MS) lock()
    }
}
