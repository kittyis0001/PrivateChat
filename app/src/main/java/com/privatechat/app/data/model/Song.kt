package com.privatechat.app.data.model

/**
 * A single song result — same shape the reference web app's
 * selectedMusic/song cards use, whether it came from YouTube or
 * Jamendo. Stored on a Story as `music` (independent of `edit`: music
 * plays over both image and video stories, it's not a visual overlay).
 */
data class Song(
    val videoId: String? = null,
    val jamendoId: String? = null,
    val title: String = "",
    val artist: String = "",
    val thumbnail: String = "",
    val audioUrl: String? = null,
    val source: String = "youtube" // "youtube" | "jamendo"
) {
    constructor() : this(null, null, "", "", "", null, "youtube")

    fun id(): String = videoId ?: jamendoId ?: title
}
