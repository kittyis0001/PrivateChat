package com.privatechat.app.call

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.core.app.NotificationCompat
import com.privatechat.app.App
import com.privatechat.app.R
import com.privatechat.app.utils.NotificationAvatarFactory

/**
 * Keeps the process alive at foreground-service priority for the
 * duration of an active call, with a persistent, low-priority
 * notification the user can tap to return to CallActivity or hang up
 * from directly — the same "ongoing call" bar WhatsApp shows.
 *
 * This service deliberately does NOT own the WebRtcClient/signaling
 * itself — those still live in CallActivity exactly as before.
 * Backgrounding an Activity (pressing Home) doesn't destroy it or its
 * fields; the actual risk to a background call is the OS killing the
 * whole process under memory pressure or aggressive battery
 * optimization once nothing is visible. A foreground service with an
 * ongoing notification is precisely the mechanism Android expects
 * apps to use to say "don't kill this, real-time work is happening" —
 * so this service's only job is to hold that elevated priority (and
 * give the user a way back in) for as long as the call in
 * CallActivity is actually live.
 */
class CallForegroundService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val callerName = intent?.getStringExtra(EXTRA_CALLER_NAME) ?: "Private Chat"
        val statusText = intent?.getStringExtra(EXTRA_STATUS_TEXT) ?: "Ongoing call"
        val remoteUser = intent?.getStringExtra(EXTRA_REMOTE_USER)
        val isOutgoing = intent?.getBooleanExtra(EXTRA_IS_OUTGOING, false) ?: false

        startForeground(NOTIFICATION_ID, buildNotification(callerName, statusText, remoteUser, isOutgoing))
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?) = null

    private fun buildNotification(
        callerName: String,
        statusText: String,
        remoteUser: String?,
        isOutgoing: Boolean
    ): Notification {
        val returnIntent = Intent(this, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra(CallActivity.EXTRA_REMOTE_USER, remoteUser)
            putExtra(CallActivity.EXTRA_IS_OUTGOING, isOutgoing)
        }
        val returnPendingIntent = PendingIntent.getActivity(
            this, 0, returnIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val hangupIntent = Intent(this, CallHangupReceiver::class.java).apply {
            action = CallHangupReceiver.ACTION_HANGUP_CALL
        }
        val hangupPendingIntent = PendingIntent.getBroadcast(
            this, 3, hangupIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val avatar = NotificationAvatarFactory.create(
            resources.displayMetrics.density,
            callerName.firstOrNull() ?: '?',
            Color.parseColor("#B09EF5")
        )

        return NotificationCompat.Builder(this, App.ONGOING_CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(Color.parseColor("#7ec8f7"))
            .setLargeIcon(avatar)
            .setContentTitle(callerName)
            .setContentText(statusText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(returnPendingIntent)
            .addAction(0, "Hang up", hangupPendingIntent)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1003
        private const val EXTRA_CALLER_NAME = "caller_name"
        private const val EXTRA_STATUS_TEXT = "status_text"
        private const val EXTRA_REMOTE_USER = "remote_user"
        private const val EXTRA_IS_OUTGOING = "is_outgoing"

        /** Starts (or updates, if already running) the ongoing-call notification. */
        fun start(context: Context, callerName: String, statusText: String, remoteUser: String, isOutgoing: Boolean) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                putExtra(EXTRA_CALLER_NAME, callerName)
                putExtra(EXTRA_STATUS_TEXT, statusText)
                putExtra(EXTRA_REMOTE_USER, remoteUser)
                putExtra(EXTRA_IS_OUTGOING, isOutgoing)
            }
            // ContextCompat (not context.startForegroundService directly)
            // since that method doesn't exist below API 26 — this falls
            // back to plain startService there, which is fine: the only
            // thing that actually needs API 26+ is the startForeground()
            // call inside onStartCommand itself.
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallForegroundService::class.java))
        }
    }
}
