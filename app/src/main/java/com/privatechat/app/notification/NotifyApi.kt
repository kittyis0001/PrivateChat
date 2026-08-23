package com.privatechat.app.notification

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface NotifyApi {
    @POST("api/notify")
    suspend fun notify(
        @Header("X-Api-Secret") apiSecret: String,
        @Body body: NotifyRequest
    ): Response<NotifyResponse>
}
