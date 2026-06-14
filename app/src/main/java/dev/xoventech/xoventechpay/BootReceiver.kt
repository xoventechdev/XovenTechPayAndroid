package dev.xoventech.xoventechpay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            // Two-step boot path:
            //   1. enqueue BootSchedulerWorker (non-expedited, 30s delay)
            //   2. BootSchedulerWorker.enqueueUniqueWork(ServiceWatchdogWorker)
            //
            // This split is required because WorkManager forbids combining
            // setExpedited() with setInitialDelay(). Expedited jobs must
            // run immediately — but the actual FGS work needs an expedited
            // worker, and the boot path also needs a delay so the system
            // can settle after BOOT_COMPLETED.
            val restartRequest = OneTimeWorkRequestBuilder<BootSchedulerWorker>()
                .setInitialDelay(BOOT_DELAY_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                BootSchedulerWorker.WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                restartRequest
            )
        }
    }

    companion object {
        private const val BOOT_DELAY_SECONDS = 30L
    }
}
