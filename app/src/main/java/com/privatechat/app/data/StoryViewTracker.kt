package com.privatechat.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Which story IDs this device has already watched — same role as the
 * reference web app's localStorage viewedStoryIds array. Purely local
 * (not synced through Firebase): it only drives the ring's
 * active-vs-viewed color for the person actually looking at their
 * screen, exactly like the reference.
 */
object StoryViewTracker {
    private const val PREFS = "story_views"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun markViewed(storyId: String) {
        prefs.edit().putBoolean(storyId, true).apply()
    }

    fun hasViewed(storyId: String): Boolean = prefs.getBoolean(storyId, false)
}
