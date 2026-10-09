package com.privatechat.app.notes.data

import androidx.room.ColumnInfo
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
    val updatedAt: Long = System.currentTimeMillis(),
    // Added in kitty_notes.db v2 (additive; defaults keep old rows valid):
    /** Pastel card color as #RRGGBB, "" = default theme surface. */
    @ColumnInfo(defaultValue = "''")
    val color: String = "",
    /** True when this note is edited as a checklist (items live in note_checklist_items). */
    @ColumnInfo(defaultValue = "0")
    val isChecklist: Boolean = false,
    /** One-shot reminder time (epoch millis), null = no reminder. */
    val reminderAt: Long? = null
)
