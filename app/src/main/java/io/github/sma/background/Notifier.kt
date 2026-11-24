package io.github.sma.background

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.sma.MainActivity
import io.github.sma.R
import io.github.sma.core.steam.ConfirmationsApi

object Notifier {
    const val CHANNEL_CONFIRMATIONS = "confirmations"
    const val CHANNEL_LOGIN = "login_requests"
    const val CHANNEL_SERVICE = "service"
    const val CHANNEL_STATUS = "status"

    /** Id of the permanent foreground notification of PollService. */
    const val SERVICE_NOTIFICATION_ID = 21331

    // ConfirmationsApi.Confirmation.type values that can be approved from a notification.
    private const val TYPE_TRADE = 2
    private const val TYPE_MARKET_LISTING = 3

    private val PENDING_FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    private const val SERVICE_REQUEST_CODE = 1

    fun ensureChannels(ctx: Context) {
        val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_CONFIRMATIONS, "Confirmations", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CHANNEL_LOGIN, "Sign-in requests", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CHANNEL_SERVICE, "Background checks", NotificationManager.IMPORTANCE_MIN).apply {
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_STATUS, "Status", NotificationManager.IMPORTANCE_DEFAULT),
            )
        )
    }

    fun canPost(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }

    fun postConfirmation(ctx: Context, accountName: String, steamId: String, c: ConfirmationsApi.Confirmation) {
        if (!canPost(ctx)) return
        val summary = c.summary.joinToString(" ")
        val text = if (summary.isEmpty()) accountName else "$summary · $accountName"
        val publicVersion = NotificationCompat.Builder(ctx, CHANNEL_CONFIRMATIONS)
            .setSmallIcon(R.drawable.ic_stat_sma)
            .setContentTitle("New Steam confirmation")
            .build()
        val builder = NotificationCompat.Builder(ctx, CHANNEL_CONFIRMATIONS)
            .setSmallIcon(R.drawable.ic_stat_sma)
            .setContentTitle(c.headline)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setAutoCancel(true)
            .setGroup(groupKey(steamId))
            .setContentIntent(openIntent(ctx, codeFor("$steamId/${c.id}/open"), "confirmations/$steamId"))
        if (c.type == TYPE_TRADE || c.type == TYPE_MARKET_LISTING) {
            builder.addAction(0, "Accept", actionIntent(ctx, steamId, c.id, accept = true))
            builder.addAction(0, "Decline", actionIntent(ctx, steamId, c.id, accept = false))
        }
        post(ctx, confirmationId(steamId, c.id), builder.build())
    }

    fun postLoginRequest(ctx: Context, accountName: String, steamId: String, count: Int) {
        if (!canPost(ctx)) return
        val text = if (count > 1) "$count requests waiting, tap to review" else "Tap to review"
        val publicVersion = NotificationCompat.Builder(ctx, CHANNEL_LOGIN)
            .setSmallIcon(R.drawable.ic_stat_sma)
            .setContentTitle("New sign-in request")
            .build()
        val notification = NotificationCompat.Builder(ctx, CHANNEL_LOGIN)
            .setSmallIcon(R.drawable.ic_stat_sma)
            .setContentTitle("Sign-in request for $accountName")
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setAutoCancel(true)
            .setGroup(groupKey(steamId))
            .setContentIntent(openIntent(ctx, codeFor("$steamId/login/open"), "loginrequests/$steamId"))
            .build()
        post(ctx, codeFor("$steamId/login"), notification)
    }

    fun postStatus(ctx: Context, id: Int, title: String, text: String, route: String?) {
        if (!canPost(ctx)) return
        val notification = NotificationCompat.Builder(ctx, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_sma)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openIntent(ctx, id, route))
            .build()
        post(ctx, id, notification)
    }

    fun cancelConfirmation(ctx: Context, steamId: String, confId: String) {
        NotificationManagerCompat.from(ctx).cancel(confirmationId(steamId, confId))
    }

    fun serviceNotification(ctx: Context, status: String? = null): Notification =
        NotificationCompat.Builder(ctx, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_sma)
            .setContentTitle("Steam Mobile Authenticator")
            .setContentText(status ?: "Checking for Steam confirmations")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(openIntent(ctx, SERVICE_REQUEST_CODE, null))
            .build()

    /** Re-posts the permanent service notification (same id) with the given status text. */
    fun updateServiceNotification(ctx: Context, status: String) {
        post(ctx, SERVICE_NOTIFICATION_ID, serviceNotification(ctx, status))
    }

    private fun confirmationId(steamId: String, confId: String): Int = codeFor("$steamId/$confId")

    private fun codeFor(key: String): Int = key.hashCode()

    private fun groupKey(steamId: String) = "sma.account.$steamId"

    private fun openIntent(ctx: Context, requestCode: Int, route: String?): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (route != null) putExtra(NavExtras.EXTRA_ROUTE, route)
        }
        return PendingIntent.getActivity(ctx, requestCode, intent, PENDING_FLAGS)
    }

    private fun actionIntent(ctx: Context, steamId: String, confId: String, accept: Boolean): PendingIntent {
        val kind = if (accept) "accept" else "decline"
        val intent = Intent(ctx, ConfirmActionActivity::class.java).apply {
            putExtra(ConfirmActionActivity.EXTRA_STEAM_ID, steamId)
            putExtra(ConfirmActionActivity.EXTRA_CONF_ID, confId)
            putExtra(ConfirmActionActivity.EXTRA_ACCEPT, accept)
        }
        return PendingIntent.getActivity(ctx, codeFor("$steamId/$confId/$kind"), intent, PENDING_FLAGS)
    }

    @SuppressLint("MissingPermission")
    private fun post(ctx: Context, id: Int, notification: Notification) {
        if (!canPost(ctx)) return
        NotificationManagerCompat.from(ctx).notify(id, notification)
    }
}
