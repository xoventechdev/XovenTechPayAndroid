package dev.xoventech.xoventechpay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues.TAG
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager

/**
 * Background service that provides a VISUAL INDICATOR (notification) that
 * the SMS gateway is configured.
 *
 * IMPORTANT: This service is NOT required for SMS receiving or forwarding.
 * [SmsReceiver] handles SMS independently via WorkManager.
 *
 * This service tries to run as a foreground service, but if the FGS quota
 * is exhausted (Android 15 allows only 6h/24h for DATA_SYNC, 12h/24h for
 * REMOTE_MESSAGING), it gracefully falls back to a regular service with
 * just a posted notification — no crash.
 */
class SmsGatewayService : Service() {

    companion object {
        const val CHANNEL_ID = "sms_gateway_service"
        const val NOTIFICATION_ID = 1001

        @Volatile
        var isRunning: Boolean = false
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true

        // Try to start as foreground. If quota is exhausted, fall back
        // gracefully to a regular service — NO CRASH.
        try {
            startAsForeground()
            Log.d(TAG, "Started as foreground service")
        } catch (e: Exception) {
            Log.w(TAG, "FGS quota exhausted, running as regular service: ${e.message}")
            // Post a regular notification so the user still sees the app is "active"
            showNotificationOnly()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        // Remove the notification when service dies
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun startAsForeground() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Fall back: show a notification via NotificationManager without
     * calling startForeground(). The notification is NOT tied to the
     * service lifecycle, so it won't prevent the service from being
     * killed — but it gives the user a visual indicator.
     */
    private fun showNotificationOnly() {
        val notification = createNotification()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("XovenTech SMS Gateway")
            .setContentText("Listening for incoming transactions...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setShowWhen(false)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "Task removed — rescheduling heartbeat")
        SmsReceiver.scheduleHeartbeat(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SMS Gateway Service",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Ensures the SMS gateway remains active in the background"
            channel.setShowBadge(false)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
