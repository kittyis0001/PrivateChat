package com.privatechat.app.notification

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Called right after ChatRepository.sendMessage()'s Firebase write
 * succeeds (see ChatActivity) — "Android writes a message to
 * Firebase, Android immediately calls the Render API" from the spec.
 * The actual network call happens in NotifyWorker; this just builds
 * and enqueues the work request.
 */
class NotificationRepository(private val context: Context) {

    fun notifyNewMessage(senderId: String, receiverId: String, senderName: String, preview: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val data = workDataOf(
            NotifyWorker.KEY_SENDER_ID to senderId,
            NotifyWorker.KEY_RECEIVER_ID to receiverId,
            NotifyWorker.KEY_SENDER_NAME to senderName,
            NotifyWorker.KEY_PREVIEW to preview
        )

        val request = OneTimeWorkRequestBuilder<NotifyWorker>()
            .setConstraints(constraints)
            .setInputData(data)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()

        // APPEND (not REPLACE/KEEP) with a shared unique name: each
        // message still gets its own push attempt queued in order,
        // but they're chained under one WorkManager entry instead of
        // running as untracked, unbounded parallel work items if the
        // user sends several messages while offline.
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "notify_new_message"
    }
}
