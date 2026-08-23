package com.privatechat.app.notification

import com.privatechat.app.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object NotificationApiClient {

    // BuildConfig.BACKEND_BASE_URL / BACKEND_API_SECRET come from
    // app/build.gradle.kts — set them there once the Render backend is
    // deployed (see backend/README.md, step 5). Left as placeholders
    // otherwise, so a misconfigured build fails requests loudly (a
    // network error against a fake host) rather than silently pointing
    // at nothing.
    private val api: NotifyApi by lazy {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            // BODY level would log message previews to Logcat — headers
            // only, since preview text is still a private message.
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.HEADERS
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl(BuildConfig.BACKEND_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(NotifyApi::class.java)
    }

    fun get(): NotifyApi = api
}
