package com.privatechat.app.notification

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.privatechat.app.BuildConfig
import java.io.IOException

/**
 * Does the actual POST /api/notify call. WorkManager (not this class)
 * is what provides the "offline queue" and "reconnect logic" the task
 * asked for: it's enqueued with a NetworkType.CONNECTED constraint
 * (see NotificationRepository), so if the device has no connectivity
 * right when a message is sent, WorkManager holds the request and
 * fires it the moment connectivity returns — surviving app restarts
 * and device reboots, without any hand-rolled queue/persistence code
 * here.
 */
class NotifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val senderId = inputData.getString(KEY_SENDER_ID) ?: return Result.failure()
        val receiverId = inputData.getString(KEY_RECEIVER_ID) ?: return Result.failure()
        val senderName = inputData.getString(KEY_SENDER_NAME) ?: return Result.failure()
        val preview = inputData.getString(KEY_PREVIEW) ?: return Result.failure()

        return try {
            val response = NotificationApiClient.get().notify(
                BuildConfig.BACKEND_API_SECRET,
                NotifyRequest(senderId, receiverId, senderName, preview)
            )
            when {
                response.isSuccessful -> Result.success()
                // 4xx here means the request itself is malformed/
                // unauthorized (bad secret, unknown user) — retrying
                // the exact same request will never succeed, so don't
                // burn the retry budget on it.
                response.code() in 400..499 -> Result.failure()
                // 5xx / anything else is worth another attempt.
                else -> Result.retry()
            }
        } catch (e: IOException) {
            // No connectivity, DNS failure, timeout, Render cold-start
            // taking too long, etc. — exactly the transient case
            // WorkManager's retry/backoff and CONNECTED constraint
            // exist for.
            Result.retry()
        }
    }

    companion object {
        const val KEY_SENDER_ID = "senderId"
        const val KEY_RECEIVER_ID = "receiverId"
        const val KEY_SENDER_NAME = "senderName"
        const val KEY_PREVIEW = "preview"
    }
}
