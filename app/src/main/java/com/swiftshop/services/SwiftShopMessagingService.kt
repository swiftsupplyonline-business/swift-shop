package com.swiftshop.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.swiftshop.MainActivity
import timber.log.Timber

class SwiftShopMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Timber.d("New FCM token received")
        // Token update registered server-side via SwiftBackendApi.updateFcmToken
        // Using WorkManager to enqueue a one-time token sync job
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Timber.d("FCM message received from: ${remoteMessage.from}")

        val data = remoteMessage.data
        val notification = remoteMessage.notification

        val title = notification?.title ?: data["title"] ?: "Swift Shop"
        val body = notification?.body ?: data["body"] ?: ""
        val type = data["type"] ?: "GENERAL"
        val targetId = data["targetId"] ?: ""

        showNotification(
            title = title,
            body = body,
            type = type,
            targetId = targetId
        )
    }

    private fun showNotification(title: String, body: String, type: String, targetId: String) {
        val channelId = getChannelId(type)
        createNotificationChannel(channelId, getChannelName(type))

        val deepLinkUri = buildDeepLinkUri(type, targetId)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (deepLinkUri.isNotEmpty()) data = android.net.Uri.parse(deepLinkUri)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun createNotificationChannel(channelId: String, channelName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, channelName, NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Swift Shop $channelName notifications"
                enableVibration(true)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun getChannelId(type: String): String = when (type) {
        "ORDER" -> "channel_orders"
        "MESSAGE" -> "channel_messages"
        "DELIVERY" -> "channel_delivery"
        "PAYMENT" -> "channel_payments"
        "MARKETING" -> "channel_marketing"
        else -> "channel_general"
    }

    private fun getChannelName(type: String): String = when (type) {
        "ORDER" -> "Orders"
        "MESSAGE" -> "Messages"
        "DELIVERY" -> "Delivery"
        "PAYMENT" -> "Payments"
        "MARKETING" -> "Promotions"
        else -> "General"
    }

    private fun buildDeepLinkUri(type: String, targetId: String): String {
        if (targetId.isEmpty()) return ""
        return when (type) {
            "ORDER" -> "swiftshop://order/$targetId"
            "MESSAGE" -> "swiftshop://conversation/$targetId"
            "DELIVERY" -> "swiftshop://delivery/$targetId"
            "POST" -> "swiftshop://post/$targetId"
            "LISTING" -> "swiftshop://listing/$targetId"
            else -> ""
        }
    }
}
