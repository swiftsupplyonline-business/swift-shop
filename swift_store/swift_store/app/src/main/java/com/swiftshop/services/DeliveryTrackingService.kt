package com.swiftshop.services

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.swiftshop.MainActivity
import timber.log.Timber

class DeliveryTrackingService : Service() {

    companion object {
        const val CHANNEL_ID = "channel_delivery_tracking"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_ROUTE_ID = "routeId"

        fun buildIntent(context: android.content.Context, routeId: String) =
            Intent(context, DeliveryTrackingService::class.java).apply {
                putExtra(EXTRA_ROUTE_ID, routeId)
            }
    }

    private var routeId: String = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Timber.d("DeliveryTrackingService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        routeId = intent?.getStringExtra(EXTRA_ROUTE_ID) ?: ""
        startForeground(NOTIFICATION_ID, buildNotification("Tracking your delivery…"))
        // In a full implementation: subscribe to Firestore delivery route updates
        // and update the notification with live ETA
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
        Timber.d("DeliveryTrackingService destroyed")
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                if (routeId.isNotEmpty()) {
                    data = android.net.Uri.parse("swiftshop://delivery/$routeId")
                }
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Swift Shop Delivery")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Delivery Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active delivery tracking progress"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }
}
