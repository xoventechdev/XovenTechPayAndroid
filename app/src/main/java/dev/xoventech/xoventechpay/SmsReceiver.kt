package dev.xoventech.xoventechpay

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Telephony
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import dev.xoventech.xoventechpay.api.SmsForwardWorker
import java.util.concurrent.TimeUnit

/**
 * Receives incoming SMS broadcasts and forwards them via [SmsForwardWorker].
 *
 * This receiver is the PRIMARY and ONLY mechanism for receiving SMS.
 * It works even when no service is running — as long as the Android OS (or
 * MIUI) delivers the broadcast to your app. The foreground service
 * ([SmsGatewayService]) is OPTIONAL and serves only as a user-visible
 * indicator.
 *
 * On Xiaomi / MIUI / HyperOS devices, you MUST:
 *   1. Enable "Autostart" for this app in MIUI settings
 *   2. Disable "Battery Saver" for this app
 *   3. Lock the app in the recents screen
 *   4. Disable "MIUI Optimization" (Developer Options > MIUI Optimization)
 *
 * Without these steps, MIUI freezes the app process and the SMS broadcast
 * is silently dropped.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        Log.d(TAG, "SMS broadcast received")

        // CRITICAL: goAsync() extends the broadcast receiver's lifetime from
        // ~10 seconds to ~30 seconds. This is essential because WorkManager
        // enqueue is asynchronous and MIUI can be slow to schedule workers.
        val pendingResult = goAsync()

        try {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)

            if (messages.isEmpty()) {
                Log.w(TAG, "No SMS messages extracted from intent")
                pendingResult.finish()
                return
            }

            messages.forEach { sms ->
                val sender = sms.displayOriginatingAddress ?: "Unknown"
                val message = sms.displayMessageBody ?: ""

                Log.d(TAG, "Processing SMS from $sender: ${message.take(50)}...")

                // Schedule forwarding via WorkManager (expedited for immediate
                // execution on Android 15). This is the PRIMARY path.
                val work = OneTimeWorkRequestBuilder<SmsForwardWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setInputData(
                        Data.Builder()
                            .putString("secret", "mykpg61kha")
                            .putString("sender", sender)
                            .putString("message", message)
                            .build()
                    )
                    .build()

                WorkManager
                    .getInstance(context)
                    .enqueueUniqueWork(
                        UNIQUE_WORK_NAME,
                        ExistingWorkPolicy.APPEND_OR_REPLACE,
                        work
                    )
            }

            // IMPORTANT: Also reschedule the AlarmManager heartbeat from here.
            // This ensures that even if MIUI killed the WorkManager scheduler,
            // the next alarm will re-trigger the health check and restart
            // WorkManager if needed.
            scheduleHeartbeat(context)

        } catch (e: Exception) {
            Log.e(TAG, "Error processing SMS: ${e.message}", e)
        } finally {
            pendingResult.finish()
        }
    }

    companion object {
        private const val TAG = "SMS_DEBUG"
        private const val UNIQUE_WORK_NAME = "sms_gateway_forward"

        /** Schedule an exact alarm to fire in [intervalMs] milliseconds. */
        fun scheduleHeartbeat(context: Context, intervalMs: Long = HEARTBEAT_INTERVAL_MS) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, AlarmHeartbeatReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Use ELAPSED_REALTIME_WAKEUP so the alarm fires even when the
            // device is in deep sleep (Doze mode). This is the most reliable
            // alarm type for background operations.
            // On Android 12+ we need SCHEDULE_EXACT_ALARM permission.
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    alarmManager.canScheduleExactAlarms() -> {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + intervalMs,
                        pendingIntent
                    )
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    // Fallback for devices without exact alarm permission
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + intervalMs,
                        pendingIntent
                    )
                }
                else -> {
                    alarmManager.set(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + intervalMs,
                        pendingIntent
                    )
                }
            }

            Log.d(TAG, "Heartbeat alarm scheduled in ${intervalMs / 1000}s")
        }

        /** Cancel the heartbeat alarm. */
        fun cancelHeartbeat(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, AlarmHeartbeatReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            pendingIntent?.let { alarmManager.cancel(it) }
        }

        // Heartbeat every 5 minutes. This is frequent enough to catch MIUI
        // process kills, but not so frequent that it drains battery.
        // MIUI allows ~6 "allow while idle" alarms per 9-minute window in
        // Doze mode, so 5-minute interval stays within budget.
        // 5 * 60 * 1000 = 300,000 ms; written as a literal arithmetic
        // expression so it remains a compile-time constant under `const val`.
        private const val HEARTBEAT_INTERVAL_MS = 5L * 60L * 1000L  // 5 minutes in milliseconds

        private const val ALARM_REQUEST_CODE = 9001
    }
}
