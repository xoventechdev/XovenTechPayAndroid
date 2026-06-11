package dev.xoventech.xoventechpay.api

import retrofit2.http.Body
import retrofit2.http.POST

interface ApiService {
    @POST("api/webhooks/sms")
    suspend fun forwardSms(@Body payload: SmsPayload): SmsResponse
}
