package dev.xoventech.xoventechpay.api

data class SmsPayload(
    val secret: String,
    val sender: String,
    val message: String,
    val transactionId: String? = null
)
