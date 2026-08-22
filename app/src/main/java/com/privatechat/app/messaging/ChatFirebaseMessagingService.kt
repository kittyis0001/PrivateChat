package com.privatechat.app.messaging

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.privatechat.app.R
import com.privatechat.app.data.Session
import com.privatechat.app.ui.chat.ChatActivity

/**
 * This is the actual fix for the original architecture problem: the
 * web app had NO mechanism to receive anything while its socket was
 * dead, so it depended entirely on the WebSocket reconnecting. FCM
 * notifications are delivered by Google Play Services independently
 * of whether this app's process, Activity, or Firebase connection is
 * alive at all — including after the app is fully killed — so a
 * message is guaranteed to reach the device regardless of app state.
 */
class ChatFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val user = Session.currentUser() ?: return
        // Store the token under this user's node so the sending
        // client (or a Cloud Function, once added) knows where to
        // deliver notifications for them. Overwrites any stale token
        // automatically on every refresh.
        FirebaseDatabase.getInstance().getReference("fcmTokens").child(user).setValue(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        // Local, per-device setting (see Session.isMuted) — checked first
        // so a muted user never sees a notification, without any network
        // round trip inside the notification path.
        if (Session.isMuted()) return

        val title = message.data["senderName"] ?: message.notification?.title ?: "New message"
        val body = message.data["preview"] ?: message.notification?.body ?: "New message"

        val openChatIntent = Intent(this, ChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openChatIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, getString(R.string.default_notification_channel_id))
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(Color.parseColor("#7ec8f7"))
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
    }
}
