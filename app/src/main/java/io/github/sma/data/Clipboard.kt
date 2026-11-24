package io.github.sma.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

object SecureClipboard {
    private const val CLEAR_DELAY_MS = 60_000L

    // Same string as ClipDescription.EXTRA_IS_SENSITIVE (API 33+); older systems ignore it.
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    private val handler = Handler(Looper.getMainLooper())
    private var pendingClear: Runnable? = null

    /**
     * Copies [text] marked as sensitive and clears it after 60 s if it is still the clipboard content.
     * Call from the main thread.
     */
    @Synchronized
    fun copy(context: Context, label: String, text: String) {
        val appContext = context.applicationContext
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        val clip = ClipData.newPlainText(label, text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(EXTRA_IS_SENSITIVE, true)
        }
        clipboard.setPrimaryClip(clip)

        pendingClear?.let { handler.removeCallbacks(it) }
        val task = Runnable {
            synchronized(this) { pendingClear = null }
            clearIfStillOurs(appContext, text)
        }
        pendingClear = task
        handler.postDelayed(task, CLEAR_DELAY_MS)
    }

    private fun clearIfStillOurs(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val current = try {
            clipboard.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.text
                ?.toString()
        } catch (e: SecurityException) {
            // Clipboard read denied (app not in foreground on API 29+): cannot verify, do not touch it.
            return
        }
        if (current != text) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipboard.clearPrimaryClip()
        } else {
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }
}
