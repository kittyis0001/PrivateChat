package com.privatechat.app.messaging

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.privatechat.app.App
import com.privatechat.app.R
import com.privatechat.app.call.CallActivity
import com.privatechat.app.call.CallDeclineReceiver
import com.privatechat.app.data.Session
import com.privatechat.app.ui.chat.ChatActivity
import com.privatechat.app.utils.NotificationAvatarFactory

// Shared with CallDeclineReceiver, which needs the same ID to cancel
// this exact notification when Decline is tapped.
const val CALL_NOTIFICATION_ID = 1002

/**
 * This is the actual fix for the original architecture problem: the
 * web app had NO mechanism to receive anything while its socket was
 * dead, so it depended entirely on the WebSocket reconnecting. FCM
 * notifications are delivered by Google Play Services independently
 * of whether this app's process, Activity, or Firebase connection is
 * alive at all — including after the app is fully killed — so a
 * message is guaranteed to reach the device regardless of app state.
 *
 * The push itself is now sent by the Render backend (see backend/),
 * which reads the token this class writes to fcmTokens/{user}.
 */
class ChatFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val user = Session.currentUser() ?: return
        // Same explicit Realtime Database instance/region every other
        // Firebase call in this app uses — this previously called the
        // zero-arg getInstance() (the *default* database), which could
        // silently write the token to the wrong instance and leave the
        // backend unable to find it. Fixed here since this class is
        // exactly the notification path being overhauled.
        FirebaseDatabase.getInstance(DB_URL).getReference("fcmTokens").child(user).setValue(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        if (message.data["type"] == "call") {
            handleIncomingCallPush(message)
            return
        }

        // Local, per-device setting (see Session.isMuted) — checked first
        // so a muted user never sees a notification, without any network
        // round trip inside the notification path.
        if (Session.isMuted()) return

        // "If chat is open with sender, suppress notification" — the
        // message is already about to appear directly in the open
        // chat via the live Realtime Database listener, so showing a
        // system notification on top of it would just be noise.
        if (ChatActivity.isForeground) return

        val senderName = message.data["senderName"] ?: message.notification?.title ?: "New message"
        val preview = message.data["preview"] ?: message.notification?.body ?: "New message"

        val openChatIntent = Intent(this, ChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openChatIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val avatar = NotificationAvatarFactory.create(
            resources.displayMetrics.density,
            senderName.firstOrNull() ?: '?',
            Color.parseColor("#B09EF5")
        )

        val sender = Person.Builder()
            .setName(senderName)
            .setIcon(IconCompat.createWithBitmap(avatar))
            .build()

        // MessagingStyle (rather than a plain title/text notification)
        // is what actually produces WhatsApp/Messenger's look: sender
        // name + avatar bubble on an expandable message row.
        val messagingStyle = NotificationCompat.MessagingStyle(sender)
            .addMessage(preview, System.currentTimeMillis(), sender)

        val notification = NotificationCompat.Builder(this, getString(R.string.default_notification_channel_id))
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(Color.parseColor("#7ec8f7"))
            .setLargeIcon(avatar)
            .setStyle(messagingStyle)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(NOTIFICATION_GROUP_KEY)
            .build()

        // Reusing the same NOTIFICATION_ID on every call is what gives
        // "no duplicate notifications" — a new message updates the one
        // existing notification (MessagingStyle stacks it) instead of
        // stacking a second, separate one, matching how WhatsApp
        // collapses a single conversation into one notification.
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }

    /**
     * WhatsApp-style incoming-call notification: full-screen intent (so
     * it wakes the screen and shows over the lock screen even while the
     * app is backgrounded or fully killed — CallActivity already has
     * showWhenLocked/turnScreenOn set for exactly this), plus inline
     * Accept/Decline actions.
     *
     * Skipped if CallActivity is already showing — it got here through
     * its own live Firebase listener (app was in foreground when the
     * call started) and is already ringing on screen; a second
     * full-screen notification on top of it would just be a jarring
     * duplicate, same reasoning as ChatActivity.isForeground above.
     */
    private fun handleIncomingCallPush(message: RemoteMessage) {
        if (CallActivity.isForeground) return
        val callerId = message.data["callerId"] ?: return
        val callerName = message.data["callerName"] ?: callerId

        val fullScreenIntent = Intent(this, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(CallActivity.EXTRA_REMOTE_USER, callerId)
            putExtra(CallActivity.EXTRA_IS_OUTGOING, false)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Accept opens the same CallActivity screen (answering still
        // needs its WebRTC/mic-permission flow), just with a flag that
        // skips straight to the accept action instead of waiting for a
        // second tap once the ringing UI is on screen.
        val acceptIntent = Intent(this, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(CallActivity.EXTRA_REMOTE_USER, callerId)
            putExtra(CallActivity.EXTRA_IS_OUTGOING, false)
            putExtra(CallActivity.EXTRA_AUTO_ACCEPT, true)
        }
        val acceptPendingIntent = PendingIntent.getActivity(
            this, 1, acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declineIntent = Intent(this, CallDeclineReceiver::class.java).apply {
            action = CallDeclineReceiver.ACTION_DECLINE_CALL
        }
        val declinePendingIntent = PendingIntent.getBroadcast(
            this, 2, declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val avatar = NotificationAvatarFactory.create(
            resources.displayMetrics.density,
            callerName.firstOrNull() ?: '?',
            Color.parseColor("#B09EF5")
        )

        val notification = NotificationCompat.Builder(this, App.CALL_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(Color.parseColor("#7ec8f7"))
            .setLargeIcon(avatar)
            .setContentTitle(callerName)
            .setContentText("Incoming voice call…")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(false)
            .setOngoing(true)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .addAction(R.drawable.ic_call_end, "Decline", declinePendingIntent)
            .addAction(R.drawable.ic_call, "Accept", acceptPendingIntent)
            .build()

        NotificationManagerCompat.from(this).notify(CALL_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_GROUP_KEY = "com.privatechat.app.MESSAGES"
        private const val DB_URL = "https://private-chat-7a103-default-rtdb.asia-southeast1.firebasedatabase.app"
    }
}
