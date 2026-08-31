package com.privatechat.app.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.privatechat.app.data.Session
import com.privatechat.app.messaging.CALL_NOTIFICATION_ID

/**
 * Handles the incoming-call notification's Decline action button —
 * WhatsApp-style "decline without opening the app". Writes the same
 * "declined" + endCall() signal CallActivity.userDeclinedCall() would,
 * then dismisses the notification. Uses goAsync() because the process
 * may otherwise be frozen/killed by the OS before the async Firebase
 * write actually reaches the network.
 */
class CallDeclineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val currentUser = Session.currentUser()
        NotificationManagerCompat.from(context).cancel(CALL_NOTIFICATION_ID)
        if (currentUser == null) return

        val pendingResult = goAsync()
        val signaling = CallSignalingRepository(currentUser)
        signaling.setStatus("declined")
        signaling.endCall()
        // Both setValue calls above return immediately (fire-and-forget
        // Firebase writes) but still need a brief moment to actually
        // reach the network before the receiver's process is eligible
        // to be torn down — this delay is deliberately short since
        // goAsync() only grants a limited window before the OS considers
        // it an ANR risk.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            pendingResult.finish()
        }, 500)
    }

    companion object {
        const val ACTION_DECLINE_CALL = "com.privatechat.app.action.DECLINE_CALL"
    }
}
