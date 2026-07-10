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
import java.util.concurrent.TimeUnit

/**
 * Handles device boot and reboots.
 *
 * On Xiaomi/MIUI devices, BOOT_COMPLETED can be DELAYED by up to several
 * minutes. The two-step boot path (BootSchedulerWorker → ServiceWatchdogWorker)
 * handles this gracefully with a 30-second initial delay.
 *
 * NEW: Also schedules the AlarmManager heartbeat as a backup. MIUI may
 * suppress WorkManager execution after boot, but AlarmManager alarms
 * registered from BOOT_COMPLETED are generally respected.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.miui.home.action.LAUNCH"
        ) return

        Log.d(TAG, "Boot broadcast received: $action")

        // Step 1: Start the gateway service immediately (best-effort).
        // This may fail on Android 15 with ForegroundServiceStartNotAllowedException,
        // but it's worth trying because right after boot the app hasn't been
        // "in background" yet.
        try {
            val serviceIntent = Intent(context, SmsGatewayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.d(TAG, "Gateway service start requested from boot")
        } catch (e: Exception) {
            Log.w(TAG, "Cannot start FGS from boot receiver: ${e.message}")
        }

        // Step 2: Enqueue the two-step WorkManager chain (delayed).
        //   - BootSchedulerWorker runs after 30s (lets system settle)
        //   - BootSchedulerWorker then enqueues ServiceWatchdogWorker (expedited)
        val restartRequest = OneTimeWorkRequestBuilder<BootSchedulerWorker>()
            .setInitialDelay(BOOT_DELAY_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            BootSchedulerWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            restartRequest
        )

        // Step 3: Schedule the AlarmManager heartbeat immediately.
        // This fires in 60 seconds (shorter than usual) to catch cases where
        // WorkManager is slow to initialize after boot.
        SmsReceiver.scheduleHeartbeat(
            context,
            intervalMs = BOOT_HEARTBEAT_DELAY_MS
        )

        Log.d(TAG, "Boot recovery initiated: service + WorkManager + AlarmManager")
    }

    companion object {
        private const val TAG = "BootReceiver"
        private const val BOOT_DELAY_SECONDS = 30L

        // First heartbeat 60 seconds after boot, then every 5 minutes
        private const val BOOT_HEARTBEAT_DELAY_MS = 60_000L
    }
}
