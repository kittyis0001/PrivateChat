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
