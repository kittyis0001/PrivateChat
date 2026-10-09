package com.privatechat.app.notes.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Repository for the isolated Notes module. All writes run on IO and
 * only ever touch the Notes Room database — never Firebase or any
 * chat data.
 */
class NotesRepository(context: Context) {

    private val db = NotesDatabase.get(context)
    private val notes = db.notesDao()
    private val categories = db.categoryDao()
    private val stickies = db.stickyDao()

    companion object {
        val DEFAULT_CATEGORIES = listOf("Personal", "Study", "Work", "Shopping List", "Important")
    }

    // ---- notes ----
    fun observeActive(): Flow<List<Note>> = notes.observeActive()
    fun observeActiveByCategory(id: Long): Flow<List<Note>> = notes.observeActiveByCategory(id)
    fun observeActiveNoTag(): Flow<List<Note>> = notes.observeActiveNoTag()
    fun searchActive(q: String): Flow<List<Note>> = notes.searchActive(q.trim())
    fun observeArchived(): Flow<List<Note>> = notes.observeArchived()
    fun observeTrash(): Flow<List<Note>> = notes.observeTrash()
    fun observeArchivedCount(): Flow<Int> = notes.observeArchivedCount()
    fun observeTrashCount(): Flow<Int> = notes.observeTrashCount()

    suspend fun getNote(id: Long): Note? = withContext(Dispatchers.IO) { notes.getById(id) }

    suspend fun saveNote(note: Note): Long = withContext(Dispatchers.IO) {
        if (note.id == 0L) {
            notes.insert(note)
        } else {
            notes.update(note.copy(updatedAt = System.currentTimeMillis()))
            note.id
        }
    }

    suspend fun setPinned(note: Note, pinned: Boolean) = withContext(Dispatchers.IO) {
        notes.update(note.copy(pinned = pinned, updatedAt = System.currentTimeMillis()))
    }

    suspend fun setArchived(note: Note, archived: Boolean) = withContext(Dispatchers.IO) {
        notes.update(note.copy(archived = archived, updatedAt = System.currentTimeMillis()))
    }

    /** Soft-delete into Trash (recoverable). */
    suspend fun moveToTrash(note: Note) = withContext(Dispatchers.IO) {
        notes.update(note.copy(deletedAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()))
    }

    suspend fun restoreFromTrash(note: Note) = withContext(Dispatchers.IO) {
        notes.update(note.copy(deletedAt = null, updatedAt = System.currentTimeMillis()))
    }

    /** Permanent delete — Notes table only. */
    suspend fun deleteForever(note: Note) = withContext(Dispatchers.IO) { notes.delete(note) }

    suspend fun getAllForBackup(): List<Note> = withContext(Dispatchers.IO) { notes.getAllForBackup() }

    // ---- categories ----
    fun observeCategories(): Flow<List<Category>> = categories.observeAll()

    suspend fun ensureDefaultCategories() = withContext(Dispatchers.IO) {
        if (categories.count() == 0) {
            DEFAULT_CATEGORIES.forEach { categories.insert(Category(name = it, isDefault = true)) }
        }
    }

    suspend fun addCategory(name: String): Long = withContext(Dispatchers.IO) {
        categories.insert(Category(name = name.trim()))
    }

    suspend fun renameCategory(category: Category, newName: String) = withContext(Dispatchers.IO) {
        categories.update(category.copy(name = newName.trim()))
    }

    /** Removing a category keeps its notes; they become "No Tag". */
    suspend fun removeCategory(category: Category) = withContext(Dispatchers.IO) {
        notes.clearCategory(category.id)
        categories.delete(category)
    }

    suspend fun getCategoriesOnce(): List<Category> = withContext(Dispatchers.IO) { categories.getAllOnce() }

    // ---- sticky notes ----
    fun observeStickies(): Flow<List<StickyNote>> = stickies.observeAll()

    suspend fun saveSticky(sticky: StickyNote): Long = withContext(Dispatchers.IO) {
        if (sticky.id == 0L) stickies.insert(sticky)
        else { stickies.update(sticky.copy(updatedAt = System.currentTimeMillis())); sticky.id }
    }

    suspend fun deleteSticky(sticky: StickyNote) = withContext(Dispatchers.IO) { stickies.delete(sticky) }

    suspend fun getStickiesOnce(): List<StickyNote> = withContext(Dispatchers.IO) { stickies.getAllOnce() }

    suspend fun importSticky(sticky: StickyNote) = withContext(Dispatchers.IO) {
        stickies.insert(sticky.copy(id = 0))
    }

    suspend fun importNote(note: Note) = withContext(Dispatchers.IO) {
        notes.insert(note.copy(id = 0))
    }

    suspend fun importCategory(category: Category) = withContext(Dispatchers.IO) {
        categories.insert(category.copy(id = 0))
    }
}
