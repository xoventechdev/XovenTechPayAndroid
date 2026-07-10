package dev.xoventech.xoventechpay

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
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Periodic watchdog that keeps [SmsGatewayService] alive on Android 15+.
 *
 * This is a chained [androidx.work.OneTimeWorkRequest] — each invocation
 * re-enqueues itself with a 15-minute initial delay. This avoids the
 * 15-minute minimum interval limitation of PeriodicWorkRequest while
 * maintaining regular health checks.
 *
 * Uses FOREGROUND_SERVICE_TYPE_DATA_SYNC (6h/24h on Android 15) to match
 * the gateway service type.
 */
class ServiceWatchdogWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Only consume FGS quota when the service is actually dead.
        if (!SmsGatewayService.isRunning) {
            try {
                setForeground(getForegroundInfo())
                ContextCompat.startForegroundService(
                    applicationContext,
                    Intent(applicationContext, SmsGatewayService::class.java)
                )
                Log.d(TAG, "Watchdog restarted gateway service")
            } catch (e: Throwable) {
                // FGS quota exhausted or ForegroundServiceStartNotAllowedException
                // on Android 15. Not fatal — the AlarmManager heartbeat will
                // retry, and the SmsReceiver doesn't depend on this service.
                Log.w(TAG, "Cannot restart service via watchdog: ${e.message}")
            }
        } else {
            Log.d(TAG, "Gateway service is running — no action needed")
        }

        // Re-enqueue self for the next cycle.
        // IMPORTANT: Do NOT use setExpedited() together with setInitialDelay().
        // WorkManager forbids this — expedited jobs must run immediately.
        // The watchdog doesn't need to be expedited for its next cycle;
        // it only needs to be expedited when FIRST triggered (from
        // SmsReceiver, BootReceiver, or AlarmHeartbeatReceiver) so it
        // can call setForeground() and start the FGS.
        return try {
            val next = OneTimeWorkRequestBuilder<ServiceWatchdogWorker>()
                .setInitialDelay(WATCHDOG_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                next
            )
            Log.d(TAG, "Watchdog re-scheduled in ${WATCHDOG_INTERVAL_MINUTES}min")
            Result.success()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to re-schedule watchdog: ${e.message}")
            Result.retry()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val channelId = CHANNEL_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = applicationContext.getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(channelId) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        "SMS Gateway Watchdog",
                        NotificationManager.IMPORTANCE_MIN
                    )
                )
            }
        }
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("SMS Gateway Watchdog")
            .setContentText("Checking gateway status")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()

        // Use SPECIAL_USE — no time quota limit on Android 15
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        return ForegroundInfo(NOTIFICATION_ID, notification, type)
    }

    companion object {
        const val WORK_NAME = "sms_gateway_watchdog_chain"

        private const val TAG = "ServiceWatchdogWorker"
        private const val CHANNEL_ID = "sms_gateway_watchdog_channel"
        private const val NOTIFICATION_ID = 1002
        private const val WATCHDOG_INTERVAL_MINUTES = 15L
    }
}
