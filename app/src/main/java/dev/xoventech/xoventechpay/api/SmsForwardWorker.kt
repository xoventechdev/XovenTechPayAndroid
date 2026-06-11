package dev.xoventech.xoventechpay.api

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker

class SmsForwardWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): ListenableWorker.Result {
        val secret = inputData.getString("secret") ?: return ListenableWorker.Result.failure()
        val sender = inputData.getString("sender") ?: return ListenableWorker.Result.failure()
        val message = inputData.getString("message") ?: return ListenableWorker.Result.failure()
        val transactionId = inputData.getString("transactionId")

        // Mandatory for Android 14/15 to run immediately when app is backgrounded
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            Log.e("SmsForwardWorker", "Failed to set foreground info: ${e.message}")
        }

        return try {
            val payload = SmsPayload(
                secret = secret,
                sender = sender,
                message = message,
                transactionId = transactionId
            )
            ApiClient.apiService.forwardSms(payload)
            Log.d("SmsForwardWorker", "Successfully forwarded SMS to backend")
            ListenableWorker.Result.success()
        } catch (e: Exception) {
            Log.e("SmsForwardWorker", "Error forwarding SMS: ${e.message}")
            if (runAttemptCount < 3) {
                ListenableWorker.Result.retry()
            } else {
                ListenableWorker.Result.failure()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val channelId = "sms_gateway_channel"
        val notificationId = 1
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "SMS Gateway Service"
            val descriptionText = "Handling background SMS forwarding"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(channelId, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(appContext, channelId)
            .setContentTitle("SMS Gateway Active")
            .setContentText("Syncing transaction data...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }
}
