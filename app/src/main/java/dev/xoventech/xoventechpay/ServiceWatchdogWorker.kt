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
 * A [androidx.work.PeriodicWorkRequest] cannot be expedited, but only expedited
 * workers may promote themselves to a foreground service on Android 12+ — and
 * we need the foreground promotion to legally start [SmsGatewayService]. So
 * this worker is a chained [androidx.work.OneTimeWorkRequest]: each invocation
 * re-enqueues itself with a 15-minute initial delay. The unique work name
 * "sms_gateway_watchdog_chain" is shared with [SmsGatewayService.onTaskRemoved],
 * [BootReceiver], and [MainActivity] so that any of those triggers can seed
 * the chain and any in-flight chain is preserved (KEEP) or replaced (REPLACE)
 * depending on context.
 */
class ServiceWatchdogWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Only consume FGS quota when there is actual work to do. When the
        // service is already running, this is a no-op and consumes no quota.
        if (!SmsGatewayService.isRunning) {
            try {
                // Promote self to foreground so we are legally allowed to start
                // the gateway FGS. We use REMOTE_MESSAGING (12h quota/24h on
                // Android 15) to match the gateway service and share the same
                // quota pool as SmsForwardWorker — DATA_SYNC is only 6h/24h
                // and exhausts quickly under the 15-min re-fire cadence.
                setForeground(getForegroundInfo())
                ContextCompat.startForegroundService(
                    applicationContext,
                    Intent(applicationContext, SmsGatewayService::class.java)
                )
            } catch (e: Throwable) {
                // FGS quota exhausted or other FGSNAE — the service will be
                // restarted on the next SMS arrival via
                // SmsForwardWorker.ensureServiceRunning(), or on the next time
                // the user opens the app via MainActivity. Continue and
                // re-schedule the chain so we keep polling.
                Log.w(TAG, "Cannot promote watchdog to FGS to restart service: ${e.message}")
            }
        }

        // Re-enqueue self for the next cycle. This runs whether or not the
        // service-restart path above succeeded, so the chain stays alive even
        // when FGS quota is exhausted.
        return try {
            val next = OneTimeWorkRequestBuilder<ServiceWatchdogWorker>()
                .setInitialDelay(WATCHDOG_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                next
            )
            Result.success()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to re-schedule watchdog chain: ${e.message}")
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
        // Use REMOTE_MESSAGING (12h quota/24h on Android 15) instead of
        // DATA_SYNC (6h quota/24h) so the watchdog has more headroom and
        // shares the quota pool with the gateway service it manages.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        } else {
            0
        }
        return ForegroundInfo(NOTIFICATION_ID, notification, type)
    }

    companion object {
        /** Unique work name shared across all watchdog entry points. */
        const val WORK_NAME = "sms_gateway_watchdog_chain"

        private const val TAG = "ServiceWatchdogWorker"
        private const val CHANNEL_ID = "sms_gateway_watchdog_channel"
        private const val NOTIFICATION_ID = 1002
        private const val WATCHDOG_INTERVAL_MINUTES = 15L
    }
}
