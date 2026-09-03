package com.privatechat.app.data.model

/**
 * A single story segment, stored under stories/{key} in Firebase.
 * "edit" (text/sticker/draw/filter overlay data) is used for video
 * stories only — image edits are baked into the uploaded image
 * itself, so an image story's edit is always null. "music" is added
 * by the music PR that builds on top of this foundation.
 */
data class Story(
    var key: String = "",
    val userId: String = "",
    val type: String = "", // "image" | "video"
    val mediaUrl: String = "",
    val caption: String? = null,
    val createdAt: Long = 0L,
    val expiresAt: Long = 0L,
    val edit: StoryEdit? = null
) {
    // No-arg constructor required by Firebase's automatic deserialization.
    constructor() : this("", "", "", "", null, 0L, 0L, null)

    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = expiresAt in 1 until now
}

/** All of one user's still-active stories, oldest first — matches
 * story.js's groupByUser(). */
data class StoryGroup(
    val userId: String,
    val stories: List<Story>
)
