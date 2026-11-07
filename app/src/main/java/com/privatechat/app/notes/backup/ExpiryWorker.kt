package com.privatechat.app.notes.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.privatechat.app.notes.data.NotesRepository
import com.privatechat.app.notes.reminder.ReminderManager
import java.util.concurrent.TimeUnit

/**
 * Daily self-destruct sweep: notes past their expiry time move into
 * Trash (recoverable — Trash auto-cleans after 30 days), and their
 * reminders are cancelled. Notes only, no cloud.
 */
class ExpiryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = NotesRepository(applicationContext)
        val expired = runCatching { repo.moveExpiredNotesToTrash() }.getOrElse { return Result.retry() }
        expired.forEach { ReminderManager.cancel(applicationContext, it.id) }
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "kitty_notes_expiry_check"

        /** Schedules the daily sweep (kept, never duplicated). */
        fun scheduleDaily(context: Context) {
            val request = PeriodicWorkRequestBuilder<ExpiryWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
