package com.privatechat.app.notes.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** DAO for note photos and checklist rows (kitty_notes.db v2 additions). */
@Dao
interface NoteExtrasDao {

    // ---- photos ----
    @Query("SELECT * FROM note_images WHERE noteId = :noteId ORDER BY createdAt ASC, id ASC")
    fun observeImages(noteId: Long): Flow<List<NoteImage>>

    @Query("SELECT * FROM note_images WHERE noteId = :noteId ORDER BY createdAt ASC, id ASC")
    suspend fun imagesFor(noteId: Long): List<NoteImage>

    @Insert
    suspend fun insertImage(image: NoteImage): Long

    @Query("DELETE FROM note_images WHERE id = :imageId")
    suspend fun deleteImage(imageId: Long)

    @Query("DELETE FROM note_images WHERE noteId = :noteId")
    suspend fun deleteImagesFor(noteId: Long)

    @Query("SELECT noteId, COUNT(*) AS cnt FROM note_images GROUP BY noteId")
    fun observeImageCounts(): Flow<List<ImageCount>>

    // ---- checklist ----
    @Query("SELECT * FROM note_checklist_items WHERE noteId = :noteId ORDER BY position ASC, id ASC")
    suspend fun itemsFor(noteId: Long): List<ChecklistItem>

    @Insert
    suspend fun insertItem(item: ChecklistItem): Long

    @Update
    suspend fun updateItem(item: ChecklistItem)

    @Query("DELETE FROM note_checklist_items WHERE id = :itemId")
    suspend fun deleteItem(itemId: Long)

    @Query("DELETE FROM note_checklist_items WHERE noteId = :noteId")
    suspend fun deleteItemsFor(noteId: Long)
}

/** Row type for the per-note photo count used by the home list badge. */
data class ImageCount(val noteId: Long, val cnt: Int)
