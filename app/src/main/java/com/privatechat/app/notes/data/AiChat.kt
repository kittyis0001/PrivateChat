package com.privatechat.app.notes.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One saved AI chat (a conversation). */
@Entity(tableName = "ai_chats")
data class AiChat(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** The note this chat is about, if it started from a note — empty otherwise. */
    val contextText: String = "",
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * One message inside a saved AI chat. role is "user" or
 * "assistant". The image is the JPEG bytes of an attached or
 * generated picture (small: at most ~1600px), stored as a BLOB so a
 * chat needs no extra files.
 */
@Entity(tableName = "ai_chat_messages")
data class AiChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    val role: String,
    val text: String,
    val image: ByteArray? = null,
    val createdAt: Long
)
