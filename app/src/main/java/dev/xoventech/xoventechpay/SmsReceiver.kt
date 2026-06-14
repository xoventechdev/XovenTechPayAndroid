package dev.xoventech.xoventechpay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import dev.xoventech.xoventechpay.api.SmsForwardWorker

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        Log.d(TAG, "SMS received")

        // Keep the broadcast alive until WorkManager.enqueue() actually completes.
        // Without goAsync() the system can kill the broadcast mid-enqueue and the SMS
        // is dropped on the floor. On Android 15 this is more aggressive.
        val pendingResult = goAsync()

        try {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)

            messages.forEach { sms ->
                val sender = sms.displayOriginatingAddress ?: "Unknown"
                val message = sms.displayMessageBody ?: ""

                Log.d(TAG, "Processing SMS from $sender")

                // Expedited work ensures immediate execution on Android 15.
                // Multipart SMS uses APPEND_OR_REPLACE so all parts flow through
                // the same chain name and the server can de-duplicate by sender +
                // message if needed. Do NOT start a foreground service from here
                // — that throws ForegroundServiceStartNotAllowedException on
                // Android 15 once the app has been in the background for >10s.
                // The worker (and the periodic ServiceWatchdogWorker) will keep
                // SmsGatewayService alive instead.
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
        } finally {
            pendingResult.finish()
        }
    }

    companion object {
        private const val TAG = "SMS_DEBUG"
        private const val UNIQUE_WORK_NAME = "sms_gateway_forward"
    }
}
