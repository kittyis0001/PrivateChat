package com.privatechat.app.data.repository

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.privatechat.app.data.model.Story
import com.privatechat.app.data.model.StoryGroup

/**
 * Firebase-backed story storage — same database this app already
 * uses for everything else (chat, presence, nicknames), rather than
 * standing up a separate backend collection the way the web
 * reference's Node backend does. Media itself goes through the
 * existing CloudinaryUploader, same as image/video chat messages.
 *
 * Stories expire 24h after posting, Instagram-style. There's no
 * server-side cron job in this app's small Render backend, so expiry
 * is enforced lazily: observeStories() filters out anything past its
 * expiresAt on every read (client never sees a stale story) and also
 * deletes those nodes in the background so they don't pile up.
 */
class StoryRepository(private val currentUser: String) {

    private val db: FirebaseDatabase =
        FirebaseDatabase.getInstance("https://private-chat-7a103-default-rtdb.asia-southeast1.firebasedatabase.app")
    private val storiesRef: DatabaseReference = db.getReference("stories")
    private val storyViewsRef: DatabaseReference = db.getReference("storyViews")

    private var storiesListener: ValueEventListener? = null

    companion object {
        const val STORY_LIFETIME_MS = 24 * 60 * 60 * 1000L
    }

    fun createStory(
        type: String,
        mediaUrl: String,
        caption: String?,
        edit: com.privatechat.app.data.model.StoryEdit?,
        music: com.privatechat.app.data.model.Song?,
        onComplete: (success: Boolean) -> Unit
    ) {
        val ref = storiesRef.push()
        val now = System.currentTimeMillis()
        val data = mutableMapOf<String, Any>(
            "userId" to currentUser,
            "type" to type,
            "mediaUrl" to mediaUrl,
            "createdAt" to now,
            "expiresAt" to (now + STORY_LIFETIME_MS)
        )
        if (!caption.isNullOrBlank()) data["caption"] = caption
        // Firebase's setValue() serializes nested Kotlin data classes
        // (and lists of them) via reflection on their public getters,
        // same as every other complex field this app already writes
        // this way — no manual Map<String,Any> conversion needed.
        if (edit != null) data["edit"] = edit
        if (music != null) data["music"] = music
        ref.setValue(data)
            .addOnSuccessListener { onComplete(true) }
            .addOnFailureListener { onComplete(false) }
    }

    /**
     * Live-observes all active stories grouped by user, own stories
     * first — mirrors story.js's fetchStories()+groupByUser(). Keeps
     * listening until stopObserving() is called.
     */
    fun observeStories(onChanged: (List<StoryGroup>) -> Unit) {
        stopObserving()
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val now = System.currentTimeMillis()
                val active = mutableListOf<Story>()
                for (child in snapshot.children) {
                    val story = child.getValue(Story::class.java) ?: continue
                    story.key = child.key ?: continue
                    if (story.isExpired(now)) {
                        // Lazy cleanup — see class doc comment. Fire and
                        // forget; doesn't block this read.
                        storiesRef.child(story.key).removeValue()
                        storyViewsRef.child(story.key).removeValue()
                    } else {
                        active.add(story)
                    }
                }
                val grouped = active
                    .groupBy { it.userId }
                    .map { (userId, stories) -> StoryGroup(userId, stories.sortedBy { it.createdAt }) }
                    .sortedBy { if (it.userId == currentUser) 0 else 1 }
                onChanged(grouped)
            }

            override fun onCancelled(error: DatabaseError) {
                onChanged(emptyList())
            }
        }
        storiesRef.addValueEventListener(listener)
        storiesListener = listener
    }

    fun stopObserving() {
        storiesListener?.let { storiesRef.removeEventListener(it) }
        storiesListener = null
    }

    /** One-shot snapshot for the viewer to navigate — deliberately not
     * live, so a new story arriving mid-view can't reshuffle the list
     * the person is currently paging through. */
    fun fetchStoriesOnce(onLoaded: (List<StoryGroup>) -> Unit) {
        storiesRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val now = System.currentTimeMillis()
                val active = mutableListOf<Story>()
                for (child in snapshot.children) {
                    val story = child.getValue(Story::class.java) ?: continue
                    story.key = child.key ?: continue
                    if (!story.isExpired(now)) active.add(story)
                }
                val grouped = active
                    .groupBy { it.userId }
                    .map { (userId, stories) -> StoryGroup(userId, stories.sortedBy { it.createdAt }) }
                    .sortedBy { if (it.userId == currentUser) 0 else 1 }
                onLoaded(grouped)
            }

            override fun onCancelled(error: DatabaseError) = onLoaded(emptyList())
        })
    }

    fun markViewed(storyId: String) {
        storyViewsRef.child(storyId).child(currentUser).setValue(true)
    }

    fun hasViewed(storyId: String, callback: (Boolean) -> Unit) {
        storyViewsRef.child(storyId).child(currentUser)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) = callback(snapshot.exists())
                override fun onCancelled(error: DatabaseError) = callback(false)
            })
    }

    /** Live count of distinct viewers for one of my own stories (for a
     * future "seen by" list) — not used by the viewer UI yet, kept
     * simple and available for later. */
    fun observeViewerCount(storyId: String, onChanged: (Int) -> Unit) {
        storyViewsRef.child(storyId).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) = onChanged(snapshot.childrenCount.toInt())
            override fun onCancelled(error: DatabaseError) = onChanged(0)
        })
    }

    fun deleteStory(storyId: String, onComplete: (success: Boolean) -> Unit) {
        storiesRef.child(storyId).removeValue()
            .addOnSuccessListener {
                storyViewsRef.child(storyId).removeValue()
                onComplete(true)
            }
            .addOnFailureListener { onComplete(false) }
    }
}
