package dev.xoventech.xoventechpay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.util.Log
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.OutOfQuotaPolicy
import dev.xoventech.xoventechpay.api.SmsForwardWorker

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
            return

        Log.d("SMS_DEBUG", "SMS received")

        // Ensure the main gateway service is running (Watchdog mechanism)
        val serviceIntent = Intent(context, SmsGatewayService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e("SMS_DEBUG", "Failed to restart service: ${e.message}")
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)

        messages.forEach { sms ->
            val sender = sms.displayOriginatingAddress ?: "Unknown"
            val message = sms.displayMessageBody ?: ""

            Log.d("SMS_DEBUG", "Processing SMS from $sender")

            // Expedited work ensures immediate execution on Android 15
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
                .enqueue(work)
        }
    }
}