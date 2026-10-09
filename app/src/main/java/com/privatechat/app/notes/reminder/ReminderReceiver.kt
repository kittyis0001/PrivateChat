package com.privatechat.app.notes.reminder

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.privatechat.app.R
import com.privatechat.app.notes.data.NotesRepository
import com.privatechat.app.notes.security.NotesLockManager
import com.privatechat.app.notes.ui.NoteEditorActivity
import com.privatechat.app.notes.ui.NotesActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fires a note reminder. Shows a notification on the dedicated Notes
 * reminders channel, then clears the one-shot reminder in the DB.
 * If the Notes lock is on, tapping opens the locked Notes home
 * instead of the note, so a reminder never bypasses the lock.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderManager.ACTION_FIRE) return
        val noteId = intent.getLongExtra(ReminderManager.EXTRA_NOTE_ID, 0L)
        if (noteId == 0L) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = NotesRepository(context)
                val note = repo.getNote(noteId)
                // One-shot: clear regardless of what happens below, so
                // a fired reminder never repeats on the next reschedule.
                repo.clearReminder(noteId)
                if (note != null && note.deletedAt == null && !note.archived) {
                    showNotification(context, note.id, note.title, note.content)
                }
            } catch (_: Exception) {
                // A reminder must never crash the app process.
            } finally {
                pending.finish()
            }
        }
    }

    private fun showNotification(context: Context, noteId: Long, title: String, content: String) {
        ReminderManager.ensureChannel(context)
        val locked = NotesLockManager.isLockEnabled(context) && !NotesLockManager.unlockedThisProcess
        val tapIntent = if (locked) {
            Intent(context, NotesActivity::class.java)
        } else {
            Intent(context, NoteEditorActivity::class.java)
                .putExtra(NoteEditorActivity.EXTRA_NOTE_ID, noteId)
        }.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPi = PendingIntent.getActivity(
            context,
            noteId.toInt(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = content.replace("\n", " ").take(160)
        val notification = NotificationCompat.Builder(context, ReminderManager.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notes_bell)
            .setContentTitle(title.ifBlank { context.getString(R.string.notes_reminder_title) })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(tapPi)
            .setAutoCancel(true)
            .build()
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            runCatching { NotificationManagerCompat.from(context).notify(noteId.toInt(), notification) }
        }
    }
}
