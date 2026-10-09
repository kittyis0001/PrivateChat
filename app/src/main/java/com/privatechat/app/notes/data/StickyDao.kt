package com.privatechat.app.notes.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface StickyDao {
    @Query("SELECT * FROM sticky_notes ORDER BY pinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<StickyNote>>

    @Query("SELECT * FROM sticky_notes ORDER BY updatedAt DESC")
    suspend fun getAllOnce(): List<StickyNote>

    @Insert
    suspend fun insert(sticky: StickyNote): Long

    @Update
    suspend fun update(sticky: StickyNote)

    @Delete
    suspend fun delete(sticky: StickyNote)
}
