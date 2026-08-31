package com.privatechat.app.notification

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.privatechat.app.BuildConfig
import java.io.IOException

/**
 * Does the actual POST /api/notify-call call — same shape as
 * NotifyWorker (message notifications), kept as its own class rather
 * than a generalized one so a burst of chat messages queued under
 * NotifyWorker's unique work name can never delay an urgent call ring
 * behind them; this has its own separate unique work name in
 * NotificationRepository.
 */
class CallNotifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val callerId = inputData.getString(KEY_CALLER_ID) ?: return Result.failure()
        val calleeId = inputData.getString(KEY_CALLEE_ID) ?: return Result.failure()
        val callerName = inputData.getString(KEY_CALLER_NAME) ?: return Result.failure()

        return try {
            val response = NotificationApiClient.get().notifyCall(
                BuildConfig.BACKEND_API_SECRET,
                NotifyCallRequest(callerId, calleeId, callerName)
            )
            when {
                response.isSuccessful -> Result.success()
                response.code() in 400..499 -> Result.failure()
                else -> Result.retry()
            }
        } catch (e: IOException) {
            Result.retry()
        }
    }

    companion object {
        const val KEY_CALLER_ID = "callerId"
        const val KEY_CALLEE_ID = "calleeId"
        const val KEY_CALLER_NAME = "callerName"
    }
}
