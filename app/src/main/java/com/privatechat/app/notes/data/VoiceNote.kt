package com.privatechat.app.notes.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A recorded voice note attached to a note. The audio file lives in
 * app-private storage (filesDir/voice_notes/<fileName>). */
@Entity(tableName = "voice_notes")
data class VoiceNote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val fileName: String,
    val durationMs: Long = 0,
    val transcript: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
