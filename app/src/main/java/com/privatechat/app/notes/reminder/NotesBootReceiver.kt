package com.privatechat.app.notes.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Re-arms note reminders after a reboot (alarms do not survive one).
 * RECEIVE_BOOT_COMPLETED is already declared for the chat app; this
 * receiver only reads the isolated Notes database.
 */
class NotesBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ReminderManager.rescheduleAll(context)
            } catch (_: Exception) {
                // Never crash on boot.
            } finally {
                pending.finish()
            }
        }
    }
}
