package com.privatechat.app.notes.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface AiChatDao {

    @Insert
    suspend fun insertChat(chat: AiChat): Long

    @Insert
    suspend fun insertMessage(message: AiChatMessageEntity): Long

    @Query("SELECT * FROM ai_chats ORDER BY updatedAt DESC")
    suspend fun getAllChats(): List<AiChat>

    @Query("SELECT * FROM ai_chats WHERE id = :id")
    suspend fun getChat(id: Long): AiChat?

    @Query("SELECT * FROM ai_chat_messages WHERE chatId = :chatId ORDER BY createdAt ASC, id ASC")
    suspend fun getMessages(chatId: Long): List<AiChatMessageEntity>

    @Query("SELECT text FROM ai_chat_messages WHERE chatId = :chatId ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun getLastMessageText(chatId: Long): String?

    @Query("UPDATE ai_chats SET title = :title WHERE id = :id")
    suspend fun renameChat(id: Long, title: String)

    @Query("UPDATE ai_chats SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touchChat(id: Long, updatedAt: Long)

    @Query("DELETE FROM ai_chat_messages WHERE chatId = :chatId")
    suspend fun deleteMessages(chatId: Long)

    @Query("DELETE FROM ai_chats WHERE id = :id")
    suspend fun deleteChat(id: Long)

    /** Deletes the newest saved assistant message (used when an answer is regenerated). */
    @Query(
        "DELETE FROM ai_chat_messages WHERE id = (" +
            "SELECT id FROM ai_chat_messages WHERE chatId = :chatId AND role = 'assistant' " +
            "ORDER BY createdAt DESC, id DESC LIMIT 1)"
    )
    suspend fun deleteLastAssistantMessage(chatId: Long)
}
