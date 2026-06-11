package dev.xoventech.xoventechpay.api

data class SmsResponse(
    val success: Boolean,
    val matched: Boolean,
    val logEntry: SmsLogData?
)

data class SmsLogData(
    val id: String,
    val sender: String,
    val message: String,
    val date: String,
    val matched: Boolean,
    val transactionId: String?
)
