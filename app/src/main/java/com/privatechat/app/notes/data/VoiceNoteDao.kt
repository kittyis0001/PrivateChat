package com.privatechat.app.notes.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VoiceNoteDao {
    @Query("SELECT * FROM voice_notes WHERE noteId = :noteId ORDER BY createdAt ASC")
    fun observeForNote(noteId: Long): Flow<List<VoiceNote>>

    @Query("SELECT * FROM voice_notes WHERE id = :id")
    suspend fun getById(id: Long): VoiceNote?

    @Insert
    suspend fun insert(voiceNote: VoiceNote): Long

    @Query("UPDATE voice_notes SET transcript = :transcript WHERE id = :id")
    suspend fun updateTranscript(id: Long, transcript: String)

    @Delete
    suspend fun delete(voiceNote: VoiceNote)

    @Query("SELECT * FROM voice_notes WHERE noteId = :noteId")
    suspend fun allForNote(noteId: Long): List<VoiceNote>

    @Query("DELETE FROM voice_notes WHERE noteId = :noteId")
    suspend fun deleteForNote(noteId: Long)
}
