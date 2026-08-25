package com.privatechat.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import com.google.firebase.database.FirebaseDatabase
import com.privatechat.app.data.Session

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Session.init(this)
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
            val channel = NotificationChannel(
                getString(R.string.default_notification_channel_id),
                "Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "New chat message notifications"
                enableVibration(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
