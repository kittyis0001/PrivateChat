package com.privatechat.app.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * Single receiver for the call notifications' action buttons:
 *  - Decline on the incoming-call notification (works without opening
 *    the app, including from the lock screen).
 *  - Hang up on the ongoing-call notification.
 *
 * Accept is intentionally NOT handled here: accepting must (a) request
 * RECORD_AUDIO from a visible Activity and (b) start a `microphone`
 * foreground service while the app is visible (Android 14 rule), so the
 * notification's Accept action opens CallActivity with auto-accept
 * instead (see CallManager.showIncomingNotification).
 *
 * Uses goAsync() + a short grace window so the fire-and-forget Firebase
 * write (declined/ended + session cleanup) actually reaches the network
 * before the receiver's process is eligible to be torn down.
 */
class CallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DECLINE_CALL -> {
                val pending = goAsync()
                CallManager.declineFromNotification(context)
                Handler(Looper.getMainLooper()).postDelayed({ pending.finish() }, GRACE_MS)
            }
            ACTION_HANGUP_CALL -> {
                val pending = goAsync()
                CallManager.endFromNotification(context)
                Handler(Looper.getMainLooper()).postDelayed({ pending.finish() }, GRACE_MS)
            }
        }
    }

    companion object {
        const val ACTION_DECLINE_CALL = "com.privatechat.app.action.DECLINE_CALL"
        const val ACTION_HANGUP_CALL = "com.privatechat.app.action.HANGUP_CALL"
        private const val GRACE_MS = 500L
    }
}
