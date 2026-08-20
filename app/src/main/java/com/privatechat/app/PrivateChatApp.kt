package com.privatechat.app

import android.app.Application
import com.google.firebase.FirebaseApp

class PrivateChatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
    }
}
