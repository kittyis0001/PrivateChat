package com.privatechat.app.notification

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

interface NotifyApi {
    @POST("api/notify")
    suspend fun notify(
        @Header("X-Api-Secret") apiSecret: String,
        @Body body: NotifyRequest
    ): Response<NotifyResponse>

    @POST("api/notify-call")
    suspend fun notifyCall(
        @Header("X-Api-Secret") apiSecret: String,
        @Body body: NotifyCallRequest
    ): Response<NotifyResponse>

    @GET("api/music/search")
    suspend fun musicSearch(
        @Header("X-Api-Secret") apiSecret: String,
        @Query("q") query: String
    ): Response<MusicSearchResponse>

    @GET("api/music/trending")
    suspend fun musicTrending(
        @Header("X-Api-Secret") apiSecret: String
    ): Response<MusicSearchResponse>

    @POST("api/music/recommend")
    suspend fun musicRecommend(
        @Header("X-Api-Secret") apiSecret: String,
        @Body body: MusicRecommendRequest
    ): Response<MusicRecommendResponse>

    @GET("api/music/youtube-audio")
    suspend fun resolveYoutubeAudio(
        @Header("X-Api-Secret") apiSecret: String,
        @Query("videoId") videoId: String
    ): Response<YoutubeAudioResponse>
}
