package com.privatechat.app.notification

// Mirrors backend/routes/notify.js's expected body exactly — keep
// these two in sync if either side's shape ever changes.
data class NotifyRequest(
    val senderId: String,
    val receiverId: String,
    val senderName: String,
    val preview: String
)

data class NotifyResponse(
    val success: Boolean? = null,
    val reason: String? = null,
    val error: String? = null
)

// Mirrors backend/routes/notify.js's /notify-call body exactly.
data class NotifyCallRequest(
    val callerId: String,
    val calleeId: String,
    val callerName: String
)

// Mirrors backend/routes/music.js's response shapes exactly.
data class MusicSearchResponse(
    val songs: List<com.privatechat.app.data.model.Song> = emptyList()
)

data class MusicRecommendRequest(
    val caption: String
)

data class MusicRecommendResponse(
    val songs: List<com.privatechat.app.data.model.Song> = emptyList(),
    val mood: String? = null,
    val vibe: String? = null
)

// Mirrors backend/routes/music.js's /music/youtube-audio response.
data class YoutubeAudioResponse(
    val audioUrl: String? = null
)
