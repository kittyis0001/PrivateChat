package com.privatechat.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import com.google.firebase.database.FirebaseDatabase
import com.privatechat.app.data.Session
import com.privatechat.app.data.StoryViewTracker

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Session.init(this)
        StoryViewTracker.init(this)
        // Cache data feature: Firebase Realtime Database's own offline
        // disk persistence. Cached messages/status/nicknames/etc.
        // survive app restarts, the app opens with data already on
        // screen instead of a blank/loading state while waiting on the
        // network, and unchanged data isn't re-fetched every time.
        // Must be set before any FirebaseDatabase.getInstance(...) call
        // on this same URL is used anywhere else (LoginActivity,
        // ChatRepository, ChatFirebaseMessagingService all call it) -
        // Application.onCreate() is guaranteed to run before all of
        // those, so this is the one safe place for it.
        FirebaseDatabase.getInstance(
            "https://private-chat-7a103-default-rtdb.asia-southeast1.firebasedatabase.app"
        ).setPersistenceEnabled(true)
        AppCompatDelegate.setDefaultNightMode(
            if (Session.isDarkThemeEnabled()) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                getString(R.string.default_notification_channel_id),
                "Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "New chat message notifications"
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)

            // Separate, higher-urgency channel for incoming calls —
            // IMPORTANCE_HIGH plus a ringtone-category audio attribute
            // is what makes this actually ring/vibrate continuously
            // (not just a single notification buzz) and is eligible to
            // show as a full-screen incoming-call UI, matching how a
            // real call is expected to interrupt regardless of the
            // Messages channel's own settings.
            val callChannel = NotificationChannel(
                CALL_NOTIFICATION_CHANNEL_ID,
                "Incoming calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming voice call alerts"
                enableVibration(true)
                setSound(
                    android.media.RingtoneManager.getActualDefaultRingtoneUri(
                        this@App, android.media.RingtoneManager.TYPE_RINGTONE
                    ),
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
            manager.createNotificationChannel(callChannel)

            // Separate again from the ringing channel above — this one
            // backs the ongoing-call foreground service's persistent
            // notification (see CallForegroundService), which updates
            // roughly once a second while the call is live. LOW
            // importance + no sound so those updates never re-alert or
            // buzz; it only needs to sit quietly and stay visible/
            // tappable, matching WhatsApp's "return to call" bar.
            val ongoingCallChannel = NotificationChannel(
                ONGOING_CALL_CHANNEL_ID,
                "Ongoing call",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while a call is active, so you can return to it"
                setSound(null, null)
                enableVibration(false)
            }
            manager.createNotificationChannel(ongoingCallChannel)
        }
    }

    companion object {
        const val CALL_NOTIFICATION_CHANNEL_ID = "incoming_calls"
        const val ONGOING_CALL_CHANNEL_ID = "ongoing_call"
    }
}
