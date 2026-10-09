package com.privatechat.app.notes.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.privatechat.app.R
import com.privatechat.app.notes.data.Note
import com.privatechat.app.notes.data.NotesRepository

/**
 * One-shot note reminders via AlarmManager. Notes-only: uses its own
 * broadcast receiver and its own notification channel, and never
 * touches the chat app's FCM/backend notifications.
 */
object ReminderManager {

    const val ACTION_FIRE = "com.privatechat.app.notes.ACTION_REMINDER_FIRE"
    const val EXTRA_NOTE_ID = "reminder_note_id"
    const val EXTRA_NOTE_TITLE = "reminder_note_title"

    const val CHANNEL_ID = "kitty_notes_reminders"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notes_reminder_channel),
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
    }

    /** True when Android will let us fire at the exact chosen minute. */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return am.canScheduleExactAlarms()
    }

    private fun pendingIntent(context: Context, noteId: Long, title: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_NOTE_ID, noteId)
            putExtra(EXTRA_NOTE_TITLE, title)
        }
        return PendingIntent.getBroadcast(
            context,
            noteId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Schedules (or re-schedules) the alarm for a note's reminder. */
    fun schedule(context: Context, note: Note) {
        val trigger = note.reminderAt ?: return cancel(context, note.id)
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(context, note.id, note.title)
        runCatching {
            if (canScheduleExact(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            } else {
                // Falls back to a near-time alarm; Android may delay it
                // slightly while the phone is idle. The reminder UI
                // offers the system "Alarms & reminders" grant so the
                // user can make it exact.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            }
        }
    }

    fun cancel(context: Context, noteId: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { am.cancel(pendingIntent(context, noteId, "")) }
    }

    /** Keeps the alarm in line with a note's saved reminder state. */
    fun sync(context: Context, note: Note) {
        if (note.reminderAt == null || note.archived || note.deletedAt != null) {
            cancel(context, note.id)
        } else {
            schedule(context, note)
        }
    }

    /**
     * Re-arms every active note reminder. Called on boot and when the
     * Notes home opens, which also covers restores/un-archives and
     * timezone changes without any extra receivers.
     */
    suspend fun rescheduleAll(context: Context) {
        val repo = NotesRepository(context)
        repo.getNotesWithReminders().forEach { note ->
            if (note.reminderAt != null) schedule(context, note)
        }
    }
}
