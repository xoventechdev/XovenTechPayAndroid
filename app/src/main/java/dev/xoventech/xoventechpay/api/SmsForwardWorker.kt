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
import dev.xoventech.xoventechpay.SmsReceiver

/**
 * Worker that forwards a received SMS to the backend webhook.
 *
 * This worker is the ONLY component that makes network calls. It's triggered
 * by [dev.xoventech.xoventechpay.SmsReceiver] and uses expedited execution
 * for immediate processing on Android 15.
 *
 * FGS type changed to DATA_SYNC to match the manifest declaration and avoid
 * type mismatch crashes.
 */
class SmsForwardWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): ListenableWorker.Result {
        val secret = inputData.getString("secret") ?: return ListenableWorker.Result.failure()
        val sender = inputData.getString("sender") ?: return ListenableWorker.Result.failure()
        val message = inputData.getString("message") ?: return ListenableWorker.Result.failure()
        val transactionId = inputData.getString("transactionId")

        Log.d(TAG, "Forwarding SMS from $sender to webhook")

        // Promote to foreground so the network call survives app backgrounding.
        // Isolated try/catch: if expedited quota is exhausted, the worker
        // continues as a regular (non-foreground) worker.
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            Log.w(TAG, "Foreground promotion failed (quota exhausted?): ${e.message}")
        }

        return try {
            val payload = SmsPayload(
                secret = secret,
                sender = sender,
                message = message,
                transactionId = transactionId
            )
            val response = ApiClient.apiService.forwardSms(payload)
            Log.d(TAG, "SMS forwarded successfully: ${response.success}")

            // Reschedule the heartbeat alarm — keeps the chain alive
            SmsReceiver.scheduleHeartbeat(appContext)

            // Optionally restart the gateway service (visual indicator only)
            ensureServiceRunning()

            ListenableWorker.Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error forwarding SMS: ${e.message}")
            // Still reschedule heartbeat on failure — keeps the system alive
            try {
                SmsReceiver.scheduleHeartbeat(appContext)
            } catch (_: Exception) {}

            if (runAttemptCount < 3) {
                ListenableWorker.Result.retry()
            } else {
                ListenableWorker.Result.failure()
            }
        }
    }

    private fun ensureServiceRunning() {
        if (!SmsGatewayService.isRunning) {
            try {
                ContextCompat.startForegroundService(
                    appContext,
                    Intent(appContext, SmsGatewayService::class.java)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Cannot restart service from forward worker: ${e.message}")
            }
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

        // Use REMOTE_MESSAGING (12h/24h quota) — double the budget of DATA_SYNC (6h)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            )
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
