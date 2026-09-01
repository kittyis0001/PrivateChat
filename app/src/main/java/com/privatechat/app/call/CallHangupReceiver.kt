package com.privatechat.app.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.privatechat.app.data.Session

/**
 * Handles the ongoing-call notification's "Hang up" action — ends the
 * call without needing to reopen CallActivity first, same idea as
 * CallDeclineReceiver but for an already-active call rather than one
 * still ringing. CallActivity itself (if still alive in the
 * background) picks up the resulting "ended" session status through
 * its normal signaling.onSessionChanged listener and finishes/cleans
 * up exactly as if the user had tapped its own End Call button; this
 * receiver also stops the foreground service directly in case
 * CallActivity's process was already gone.
 */
class CallHangupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CallForegroundService.stop(context)

        val currentUser = Session.currentUser() ?: return
        val pendingResult = goAsync()
        val signaling = CallSignalingRepository(currentUser)
        signaling.setStatus("ended")
        signaling.endCall()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            pendingResult.finish()
        }, 500)
    }

    companion object {
        const val ACTION_HANGUP_CALL = "com.privatechat.app.action.HANGUP_CALL"
    }
}
