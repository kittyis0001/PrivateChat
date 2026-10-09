package com.privatechat.app.notes.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NotesDao {

    @Query("SELECT * FROM notes WHERE archived = 0 AND deletedAt IS NULL ORDER BY pinned DESC, updatedAt DESC")
    fun observeActive(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE archived = 0 AND deletedAt IS NULL AND categoryId = :categoryId ORDER BY pinned DESC, updatedAt DESC")
    fun observeActiveByCategory(categoryId: Long): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE archived = 0 AND deletedAt IS NULL AND categoryId IS NULL ORDER BY pinned DESC, updatedAt DESC")
    fun observeActiveNoTag(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE archived = 0 AND deletedAt IS NULL AND (title LIKE '%' || :q || '%' OR content LIKE '%' || :q || '%') ORDER BY pinned DESC, updatedAt DESC")
    fun searchActive(q: String): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE archived = 1 AND deletedAt IS NULL ORDER BY updatedAt DESC")
    fun observeArchived(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeTrash(): Flow<List<Note>>

    @Query("SELECT COUNT(*) FROM notes WHERE archived = 1 AND deletedAt IS NULL")
    fun observeArchivedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM notes WHERE deletedAt IS NOT NULL")
    fun observeTrashCount(): Flow<Int>

    @Query("SELECT * FROM notes WHERE archived = 0 AND deletedAt IS NULL ORDER BY updatedAt DESC")
    suspend fun getAllActiveOnce(): List<Note>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: Long): Note?

    @Insert
    suspend fun insert(note: Note): Long

    @Update
    suspend fun update(note: Note)

    @Delete
    suspend fun delete(note: Note)

    @Query("UPDATE notes SET categoryId = NULL WHERE categoryId = :categoryId")
    suspend fun clearCategory(categoryId: Long)

    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    suspend fun getAllForBackup(): List<Note>
}
