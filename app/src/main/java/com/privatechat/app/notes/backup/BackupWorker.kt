package com.privatechat.app.notes.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.privatechat.app.notes.data.NotesRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Daily automatic local backup of Notes data (a ZIP: notes.json plus
 * the note photos, app-private filesDir/auto_backup/). Keeps only the
 * newest 7 daily files so the phone's storage never fills up. Notes
 * only — no cloud.
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        if (doBackup(applicationContext)) Result.success() else Result.retry()

    companion object {
        const val UNIQUE_NAME = "kitty_notes_auto_backup"
        private const val MAX_KEEP = 7

        /** Writes one backup file (newest 7 kept). Used by the worker and by "Back up now". */
        suspend fun doBackup(context: Context): Boolean = runCatching {
            val repo = NotesRepository(context)
            val notes = repo.getAllForBackup()
            val photos = NotesBackup.collectPhotos(context, repo, notes)
            val zip = NotesBackup.zipBytes(
                notes, repo.getCategoriesOnce(), repo.getStickiesOnce(), photos
            )
            val dir = File(context.filesDir, "auto_backup").apply { mkdirs() }
            val name = "kitty-notes-" +
                SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".zip"
            File(dir, name).writeBytes(zip)
            val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
            files.drop(MAX_KEEP).forEach { it.delete() }
            true
        }.getOrElse { false }

        /** Schedules the daily backup (kept, never duplicated). */
        fun scheduleDaily(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder().setRequiresBatteryNotLow(true).build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
