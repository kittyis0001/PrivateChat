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
    val type: String? = null // "system" for block/unblock events; null for normal messages
) {
    // No-arg constructor required by Firebase's automatic deserialization.
    constructor() : this("", "", "", 0L, false, false, false, null)

    fun isVoice() = text.startsWith("__voice__")
    fun isGif() = text.startsWith("__gif__")
    fun voiceUrl() = text.removePrefix("__voice__")
    fun gifUrl() = text.removePrefix("__gif__")
}

data class PresenceStatus(
    val online: Boolean = false,
    val last: Long = 0L
) {
    constructor() : this(false, 0L)
}
