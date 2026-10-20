package com.quantaedge.android.data

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.quantaedge.android.MainActivity

/** Foreground display plus token-rotation handling; background notification taps are routed by FCM. */
class NotificationPushMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        NotificationPushRegistration.onNewToken(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        val recipientId = data["qe_notification_recipient_id"]?.toLongOrNull() ?: return
        if (recipientId <= 0L) return
        val title = message.notification?.title?.takeIf { it.isNotBlank() }
            ?: data["title"]?.takeIf { it.isNotBlank() } ?: "QuantaEdge"
        val body = message.notification?.body?.takeIf { it.isNotBlank() }
            ?: data["body"]?.takeIf { it.isNotBlank() } ?: "Open QuantaEdge to read this notification"
        showNotification(recipientId, title, body, data["qe_campaign_public_id"].orEmpty())
    }

    @Suppress("MissingPermission")
    private fun showNotification(recipientId: Long, title: String, body: String, campaignId: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        NotificationPushRegistration.ensureNotificationChannel(this)
        val intent = Intent(this, MainActivity::class.java)
            .setAction(NotificationPushRegistration.OPEN_ACTION)
            .putExtra("qe_notification_recipient_id", recipientId.toString())
            .putExtra("qe_campaign_public_id", campaignId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val click = PendingIntent.getActivity(this, recipientId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, NotificationPushRegistration.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title.take(100))
            .setContentText(body.take(220))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(220)))
            .setContentIntent(click).setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH).build()
        NotificationManagerCompat.from(this).notify(recipientId.hashCode(), notification)
    }
}
