package com.privatechat.app.notes.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row of a checklist-style note. The parent note keeps a plain
 * text rendering ("✓ done" / "☐ todo" lines) in its content field so
 * list previews, Notes search, share and export keep working exactly
 * as before; this table is the structured source used by the editor.
 */
@Entity(tableName = "note_checklist_items")
data class ChecklistItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val text: String = "",
    val checked: Boolean = false,
    val position: Int = 0
)
