package com.privatechat.app.notes.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Short sticky note, kept separate from regular notes. */
@Entity(tableName = "sticky_notes")
data class StickyNote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String = "",
    /** One of: pink, lavender, mint, cream, sky. */
    val color: String = "pink",
    val pinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
