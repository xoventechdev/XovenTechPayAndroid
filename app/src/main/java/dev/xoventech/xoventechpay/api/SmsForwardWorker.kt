package dev.xoventech.xoventechpay.api

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import dev.xoventech.xoventechpay.SmsGatewayService

class SmsForwardWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): ListenableWorker.Result {
        val secret = inputData.getString("secret") ?: return ListenableWorker.Result.failure()
        val sender = inputData.getString("sender") ?: return ListenableWorker.Result.failure()
        val message = inputData.getString("message") ?: return ListenableWorker.Result.failure()
        val transactionId = inputData.getString("transactionId")

        // Promote to foreground so the network call survives the broadcast
        // wrapping up. Isolated try/catch: if expedited quota is exhausted the
        // worker is demoted to non-expedited and setForeground() will throw —
        // we still want the network call to proceed.
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            Log.w(TAG, "Foreground promotion failed (likely demoted): ${e.message}")
        }

        return try {
            val payload = SmsPayload(
                secret = secret,
                sender = sender,
                message = message,
                transactionId = transactionId
            )
            ApiClient.apiService.forwardSms(payload)
            Log.d(TAG, "Successfully forwarded SMS to backend")

            // If the gateway service was killed while we were processing, bring
            // it back. We are an expedited foreground worker at this point, so
            // startForegroundService() is legal.
            ensureServiceRunning()

            ListenableWorker.Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error forwarding SMS: ${e.message}")
            if (runAttemptCount < 3) {
                ListenableWorker.Result.retry()
            } else {
                ListenableWorker.Result.failure()
            }
        }
    }

    private fun ensureServiceRunning() {
        if (!SmsGatewayService.isRunning) {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, SmsGatewayService::class.java)
            )
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val channelId = CHANNEL_ID

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

        // The gateway service is declared as foregroundServiceType="remoteMessaging"
        // in the manifest. This worker's promotion must match — otherwise the
        // system rejects the startForegroundService call.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "SmsForwardWorker"
        private const val CHANNEL_ID = "sms_gateway_channel"
        private const val NOTIFICATION_ID = 1
    }
}
