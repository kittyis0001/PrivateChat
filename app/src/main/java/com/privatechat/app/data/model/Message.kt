package com.privatechat.app.data.model

/**
 * Mirrors the existing Firebase "messages/{pushKey}" node structure
 * from the web app so both clients remain compatible against the
 * same database without a migration.
 */
data class Message(
    var key: String = "",
    val name: String = "",
    val text: String = "",
    val time: Long = 0L,
    var seen: Boolean = false,
    val deleted: Boolean = false,
    val edited: Boolean = false,
    val type: String? = null, // "system" for block/unblock events; null for normal messages
    // WhatsApp-style reply metadata. "replyTo" is the Firebase push key of
    // the message being replied to; "replyText" and "replySender" are a
    // snapshot of that message's preview text/sender taken at send time so
    // the reply bar can render even if the original message later changes.
    val replyTo: String? = null,
    val replyText: String? = null,
    val replySender: String? = null,
    // Messenger-style reactions: uid -> emoji. A user can only have one
    // active reaction per message; setting a new one overwrites theirs,
    // and repository.setReaction(key, null) clears it.
    val reactions: Map<String, String>? = null,
    // WhatsApp-style image/video messages: text holds the Cloudinary
    // URL behind an "__image__"/"__video__" prefix (same pattern as
    // "__voice__"/"__gif__" above), and caption is the optional text
    // the sender attached in the preview screen. Purely additive — a
    // node written before this feature existed simply has caption ==
    // null, which Firebase's reflection-based deserialization already
    // handles for any field it doesn't find.
    val caption: String? = null
) {
    // No-arg constructor required by Firebase's automatic deserialization.
    constructor() : this("", "", "", 0L, false, false, false, null, null, null, null, null, null)

    fun isVoice() = text.startsWith("__voice__")
    fun isGif() = text.startsWith("__gif__")
    fun isImage() = text.startsWith("__image__")
    fun isVideo() = text.startsWith("__video__")
    fun voiceUrl() = text.removePrefix("__voice__")
    fun gifUrl() = text.removePrefix("__gif__")
    fun mediaUrl() = text.removePrefix("__image__").removePrefix("__video__")
    fun hasReply() = !replyTo.isNullOrEmpty()

    // Compact "❤️2 😂1" style summary, most-used emoji first.
    fun reactionsSummary(): String? {
        if (reactions.isNullOrEmpty()) return null
        return reactions.values
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .joinToString(" ") { "${it.key}${it.value}" }
    }
}

data class PresenceStatus(
    val online: Boolean = false,
    val last: Long = 0L
) {
    constructor() : this(false, 0L)
}
