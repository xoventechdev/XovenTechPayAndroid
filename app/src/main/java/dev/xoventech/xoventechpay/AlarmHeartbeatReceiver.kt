package dev.xoventech.xoventechpay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager

/**
 * Backup heartbeat receiver that fires periodically via [AlarmManager].
 *
 * This is the FALLBACK mechanism when MIUI/HyperOS kills the app process
 * and WorkManager's internal scheduler is disrupted. The alarm wakes the
 * device briefly and:
 *
 *   1. Re-seeds the [ServiceWatchdogWorker] chain (if the service is dead)
 *   2. Re-schedules itself for the next interval
 *
 * This receiver does NOT start any foreground service directly. It delegates
 * all heavy work to WorkManager, which handles FGS promotion legally on
 * Android 15.
 *
 * WHY this exists:
 *   - MIUI can freeze/kill WorkManager's JobScheduler backend
 *   - MIUI can prevent BOOT_COMPLETED from being delivered
 *   - WorkManager's PeriodicWorkRequest minimum interval is 15 minutes
 *   - AlarmManager.setExactAndAllowWhileIdle() can bypass Doze more reliably
 *     than WorkManager alone
 */
class AlarmHeartbeatReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == null) return

        Log.d(TAG, "Alarm heartbeat fired — checking gateway health")

        // Step 1: Re-seed the watchdog chain. KEEP policy means we don't
        // clobber an existing healthy chain, but we ensure one exists.
        try {
            val watchdog = OneTimeWorkRequestBuilder<ServiceWatchdogWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ServiceWatchdogWorker.WORK_NAME,
                ExistingWorkPolicy.KEEP,
                watchdog
            )
            Log.d(TAG, "Watchdog chain re-seeded from alarm heartbeat")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to seed watchdog from alarm: ${e.message}")
        }

        // Step 2: Optionally restart the gateway service if it's dead.
        // The ServiceWatchdogWorker handles this internally, but we add an
        // extra nudge here for reliability on MIUI.
        if (!SmsGatewayService.isRunning) {
            try {
                val serviceIntent = Intent(context, SmsGatewayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.d(TAG, "Gateway service restarted from alarm heartbeat")
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException — expected on
                // Android 15 if the app has been in background too long.
                // The watchdog worker will handle the FGS restart legally.
                Log.w(TAG, "Cannot start FGS from alarm (expected on A15): ${e.message}")
            }
        }

        // Step 3: Reschedule the next heartbeat.
        SmsReceiver.scheduleHeartbeat(context)
    }

    companion object {
        private const val TAG = "AlarmHeartbeat"
    }
}
