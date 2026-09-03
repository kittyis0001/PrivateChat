package com.privatechat.app.data.repository

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.privatechat.app.BuildConfig
import com.privatechat.app.data.model.Song
import com.privatechat.app.notification.MusicRecommendRequest
import com.privatechat.app.notification.NotificationApiClient

/**
 * Search/trending/recommend go through the existing backend (needs
 * the YouTube API key kept server-side, same reasoning as every other
 * backend call in this app). Saved songs are plain per-user data, so
 * — same as stories, chat, presence, everything else — they live
 * directly in this app's own Firebase rather than needing a backend
 * endpoint at all.
 */
class MusicRepository(private val currentUser: String) {

    private val db: FirebaseDatabase =
        FirebaseDatabase.getInstance("https://private-chat-7a103-default-rtdb.asia-southeast1.firebasedatabase.app")
    private val savedRef: DatabaseReference = db.getReference("savedSongs").child(currentUser)

    suspend fun search(query: String): List<Song> {
        val response = NotificationApiClient.get().musicSearch(BuildConfig.BACKEND_API_SECRET, query)
        return if (response.isSuccessful) response.body()?.songs ?: emptyList() else emptyList()
    }

    suspend fun trending(): List<Song> {
        val response = NotificationApiClient.get().musicTrending(BuildConfig.BACKEND_API_SECRET)
        return if (response.isSuccessful) response.body()?.songs ?: emptyList() else emptyList()
    }

    /** [caption] mood-matches server-side (see backend/services/music.js) —
     * pass the story's current caption text, or blank for a generic
     * default mood. */
    suspend fun recommend(caption: String): Triple<List<Song>, String?, String?> {
        val response = NotificationApiClient.get().musicRecommend(
            BuildConfig.BACKEND_API_SECRET,
            MusicRecommendRequest(caption)
        )
        val body = if (response.isSuccessful) response.body() else null
        return Triple(body?.songs ?: emptyList(), body?.mood, body?.vibe)
    }

    fun saveSong(song: Song, onComplete: (Boolean) -> Unit) {
        savedRef.child(song.id().sanitizeKey()).setValue(song)
            .addOnSuccessListener { onComplete(true) }
            .addOnFailureListener { onComplete(false) }
    }

    fun removeSavedSong(song: Song) {
        savedRef.child(song.id().sanitizeKey()).removeValue()
    }

    fun fetchSaved(onLoaded: (List<Song>) -> Unit) {
        savedRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val songs = snapshot.children.mapNotNull { it.getValue(Song::class.java) }
                onLoaded(songs)
            }
            override fun onCancelled(error: DatabaseError) = onLoaded(emptyList())
        })
    }

    fun isSaved(song: Song, callback: (Boolean) -> Unit) {
        savedRef.child(song.id().sanitizeKey())
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) = callback(snapshot.exists())
                override fun onCancelled(error: DatabaseError) = callback(false)
            })
    }
}

// Firebase keys can't contain '.', '#', '$', '[', ']', or '/' — a
// videoId/jamendoId is already safe, but a title-based fallback id
// (see Song.id()) might not be.
private fun String.sanitizeKey(): String = replace(Regex("[.#$\\[\\]/]"), "_")
