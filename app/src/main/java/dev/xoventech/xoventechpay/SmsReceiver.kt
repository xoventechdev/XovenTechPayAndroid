package dev.xoventech.xoventechpay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dev.xoventech.xoventechpay.api.SmsForwardWorker

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {

        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
            return

        Log.d("SMS_DEBUG", "SMS received")

        val messages =
            Telephony.Sms.Intents.getMessagesFromIntent(intent)

        messages.forEach { sms ->

            val sender =
                sms.displayOriginatingAddress ?: "Unknown"

            val message =
                sms.displayMessageBody ?: ""

            Log.d("SMS_DEBUG", "Sender=$sender")

            val work =
                OneTimeWorkRequestBuilder<SmsForwardWorker>()
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