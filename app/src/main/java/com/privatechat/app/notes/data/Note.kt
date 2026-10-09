package com.privatechat.app.notes.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single note in the isolated Notes database (kitty_notes.db).
 * This table has no relation to the chat app's Firebase data.
 * deletedAt != null means the note is in Trash; archived notes are
 * hidden from the main list but preserved.
 */
@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "",
    val content: String = "",
    val categoryId: Long? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val deletedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
