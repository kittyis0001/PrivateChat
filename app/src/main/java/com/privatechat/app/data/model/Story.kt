package com.privatechat.app.data.model

/**
 * A single story segment, stored under stories/{key} in Firebase.
 * "edit" (text/sticker/draw/filter overlay data, video stories only —
 * image edits are baked into the uploaded image itself) and "music"
 * are added by the editor/music PRs that build on top of this
 * foundation; both are optional and null until those land.
 */
data class Story(
    var key: String = "",
    val userId: String = "",
    val type: String = "", // "image" | "video"
    val mediaUrl: String = "",
    val caption: String? = null,
    val createdAt: Long = 0L,
    val expiresAt: Long = 0L
) {
    // No-arg constructor required by Firebase's automatic deserialization.
    constructor() : this("", "", "", "", null, 0L, 0L)

    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = expiresAt in 1 until now
}

/** All of one user's still-active stories, oldest first — matches
 * story.js's groupByUser(). */
data class StoryGroup(
    val userId: String,
    val stories: List<Story>
)
