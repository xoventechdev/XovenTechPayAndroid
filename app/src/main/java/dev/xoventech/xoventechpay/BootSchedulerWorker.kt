package dev.xoventech.xoventechpay

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/**
 * Bridge between [BootReceiver] and [ServiceWatchdogWorker].
 *
 * WorkManager rejects `setExpedited()` + `setInitialDelay()` on the same
 * request — expedited jobs must run immediately. We want a 30-second delay
 * after `BOOT_COMPLETED` so the system can settle, but we also need the
 * eventual service-restart work to be expedited (only expedited workers can
 * legally call [androidx.work.CoroutineWorker.setForeground] and start a
 * foreground service on Android 12+).
 *
 * This worker is the non-expedited, delayed half. It does no FGS work itself;
 * it just enqueues the real [ServiceWatchdogWorker] which IS expedited and
 * runs as soon as the system is ready.
 */
class BootSchedulerWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        val starter = OneTimeWorkRequestBuilder<ServiceWatchdogWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            ServiceWatchdogWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            starter
        )
        Result.success()
    } catch (e: Throwable) {
        Result.retry()
    }

    companion object {
        /** Unique work name for the boot-time delayed enqueue. */
        const val WORK_NAME = "sms_gateway_boot_scheduler"
    }
}
