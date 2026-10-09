package com.privatechat.app.notes.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A photo attached to a note. The bitmap itself lives as a downscaled
 * JPEG inside the app-private files dir (files/kitty_note_images/);
 * only its file name is stored here. Notes-only: chat media
 * (Cloudinary/Firebase) is never touched.
 */
@Entity(tableName = "note_images")
data class NoteImage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val fileName: String,
    val createdAt: Long = System.currentTimeMillis()
)
