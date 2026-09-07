package com.privatechat.app.call

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.app.ServiceCompat
import com.privatechat.app.App
import com.privatechat.app.R
import com.privatechat.app.utils.NotificationAvatarFactory

/**
 * Holds the process at foreground-service priority for the duration of
 * an active call (dialing / connecting / ongoing) with a persistent,
 * tappable "return to call" notification and its own Hang Up action —
 * the same bar WhatsApp shows. The service does NOT own the WebRTC
 * client or signaling: those live in CallManager (process scope), so
 * the call survives the call screen being backgrounded, rotated, or
 * destroyed, and this service only has to keep the process from being
 * reclaimed.
 *
 * Started only from a visible Activity (CallActivity is on screen for
 * both outgoing dialing and incoming Accept), which is required for a
 * `microphone` foreground service on Android 14+. Incoming RINGING
 * never starts this service — the full-screen notification rings on
 * its own, so no background FGS start is ever attempted.
 */
class CallForegroundService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val callerName = intent?.getStringExtra(EXTRA_CALLER_NAME) ?: "Private Chat"
        val statusText = intent?.getStringExtra(EXTRA_STATUS_TEXT) ?: "Ongoing call"
        val remoteUser = intent?.getStringExtra(EXTRA_REMOTE_USER)
        val isOutgoing = intent?.getBooleanExtra(EXTRA_IS_OUTGOING, false) ?: false

        isRunning = true
        startAsForeground(callerName, statusText, remoteUser, isOutgoing)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground(
        callerName: String,
        statusText: String,
        remoteUser: String?,
        isOutgoing: Boolean
    ) {
        val notification = buildNotification(callerName, statusText, remoteUser, isOutgoing)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            // Failing to elevate to foreground priority should not crash
            // the call itself; audio still flows while the screen is up.
        }
    }

    private fun buildNotification(
        callerName: String,
        statusText: String,
        remoteUser: String?,
        isOutgoing: Boolean
    ): Notification {
        val returnIntent = Intent(this, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(CallActivity.EXTRA_REMOTE_USER, remoteUser)
            putExtra(CallActivity.EXTRA_IS_OUTGOING, isOutgoing)
        }
        val returnPendingIntent = PendingIntent.getActivity(
            this, 0, returnIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val hangupIntent = Intent(this, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_HANGUP_CALL
        }
        val hangupPendingIntent = PendingIntent.getBroadcast(
            this, 3, hangupIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
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
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
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

        @Volatile
        private var isRunning = false

        /** Starts (or updates, if already running) the ongoing-call notification. */
        fun start(context: Context, callerName: String, statusText: String, remoteUser: String, isOutgoing: Boolean) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                putExtra(EXTRA_CALLER_NAME, callerName)
                putExtra(EXTRA_STATUS_TEXT, statusText)
                putExtra(EXTRA_REMOTE_USER, remoteUser)
                putExtra(EXTRA_IS_OUTGOING, isOutgoing)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
                // Android 14+ may refuse a microphone FGS start if the
                // app lost visibility in the same instant — the call
                // itself still works while the screen is up, so never
                // crash the whole call over the FGS elevation.
            }
        }

        /**
         * Refreshes the ongoing notification in place (same id, so no
         * duplicate). Uses NotificationManager directly rather than
         * restarting the service, so a status update never triggers the
         * Android 14+ background-FGS-start restriction.
         */
        fun update(context: Context, callerName: String, statusText: String, remoteUser: String, isOutgoing: Boolean) {
            if (!isRunning) return
            val notification = buildNotificationStatic(context, callerName, statusText, remoteUser, isOutgoing)
            try {
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            } catch (_: SecurityException) {
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallForegroundService::class.java))
        }

        private fun buildNotificationStatic(
            context: Context,
            callerName: String,
            statusText: String,
            remoteUser: String?,
            isOutgoing: Boolean
        ): Notification {
            val returnIntent = Intent(context, CallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(CallActivity.EXTRA_REMOTE_USER, remoteUser)
                putExtra(CallActivity.EXTRA_IS_OUTGOING, isOutgoing)
            }
            val returnPendingIntent = PendingIntent.getActivity(
                context, 0, returnIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val hangupIntent = Intent(context, CallActionReceiver::class.java).apply {
                action = CallActionReceiver.ACTION_HANGUP_CALL
            }
            val hangupPendingIntent = PendingIntent.getBroadcast(
                context, 3, hangupIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val avatar = NotificationAvatarFactory.create(
                context.resources.displayMetrics.density,
                callerName.firstOrNull() ?: '?',
                Color.parseColor("#B09EF5")
            )
            return NotificationCompat.Builder(context, App.ONGOING_CALL_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(Color.parseColor("#7ec8f7"))
                .setLargeIcon(avatar)
                .setContentTitle(callerName)
                .setContentText(statusText)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(returnPendingIntent)
                .addAction(0, "Hang up", hangupPendingIntent)
                .build()
        }
    }
}
