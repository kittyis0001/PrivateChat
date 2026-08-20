package com.privatechat.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the logged-in username locally so the session survives
 * app restart, phone restart, and background/foreground transitions
 * without ever forcing a re-login — this app has exactly two fixed
 * users, so there's no token expiry concept to worry about; presence
 * and message access are gated purely by which of the two usernames
 * is stored here.
 */
object Session {
    private const val PREFS = "private_chat_session"
    private const val KEY_USERNAME = "username"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun save(username: String) {
        prefs.edit().putString(KEY_USERNAME, username).apply()
    }

    fun currentUser(): String? = prefs.getString(KEY_USERNAME, null)

    fun otherUser(): String? {
        val u = currentUser() ?: return null
        return if (u == "katis1") "kittyis0001" else "katis1"
    }

    fun clear() {
        prefs.edit().remove(KEY_USERNAME).apply()
    }

    fun isLoggedIn(): Boolean = currentUser() != null
}
