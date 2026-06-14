package dev.xoventech.xoventechpay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager

class SmsGatewayService : Service() {

    companion object {
        const val CHANNEL_ID = "sms_gateway_service"
        const val NOTIFICATION_ID = 1001

        /**
         * Liveness flag shared with [ServiceWatchdogWorker] and
         * [api.SmsForwardWorker]. Volatile so reads from the worker thread
         * see the latest value set on the main thread.
         */
        @Volatile
        var isRunning: Boolean = false
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        isRunning = true
        // START_STICKY ensures the OS attempts to restart the service if it's killed for memory
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    private fun startAsForeground() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Unobtrusive persistent notification:
        //  - PRIORITY_MIN + IMPORTANCE_MIN channel: no status-bar icon, no
        //    heads-up, no sound/vibration, only a collapsed entry in the shade.
        //  - setSilent: belt-and-suspenders against accidental sound.
        //  - setShowWhen(false): hide the timestamp row.
        //  - setOngoing(true): the user cannot dismiss it (required for a
        //    foreground service — dismissing would also kill the service).
        //  - setVisibility(SECRET): the content is hidden on the lock screen
        //    so the gateway text is not visible to anyone glancing at the
        //    phone.
        // The OS still shows its own "Foreground service" chip in the status
        // bar on Android 14+; that chip is system-controlled and cannot be
        // suppressed. Our app-level notification, however, stays out of sight.
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("XovenTech SMS Gateway")
            .setContentText("Listening for incoming transactions...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Hand the restart off to WorkManager: a self-rescheduling one-time
        // expedited worker is the only legal way to start a foreground service
        // on Android 15. AlarmManager.setAndAllowWhileIdle is throttled.
        val restartRequest = OneTimeWorkRequestBuilder<ServiceWatchdogWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            ServiceWatchdogWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            restartRequest
        )
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // IMPORTANCE_MIN keeps our notification out of the status bar and
            // out of the heads-up queue — it is only visible as a collapsed
            // entry when the user manually pulls down the notification shade.
            // The OS still considers the foreground service alive; the
            // ServiceWatchdogWorker chain will restore it if any aggressive
            // OEM battery manager decides to kill us.
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SMS Gateway Service",
                NotificationManager.IMPORTANCE_MIN
            )
            channel.description = "Ensures the SMS gateway remains active in the background"
            channel.setShowBadge(false)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
