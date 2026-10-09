package com.privatechat.app.notes.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Repository for the isolated Notes module. All writes run on IO and
 * only ever touch the Notes Room database — never Firebase or any
 * chat data.
 */
class NotesRepository(context: Context) {

    private val appContext = context.applicationContext
    private val db = NotesDatabase.get(context)
    private val notes = db.notesDao()
    private val categories = db.categoryDao()
    private val stickies = db.stickyDao()
    private val extras = db.noteExtrasDao()

    companion object {
        val DEFAULT_CATEGORIES = listOf("Personal", "Study", "Work", "Shopping List", "Important")
        const val TRASH_RETENTION_MS: Long = 30L * 24 * 60 * 60 * 1000
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

    /** Permanent delete — Notes tables + this note's photo files only. */
    suspend fun deleteForever(note: Note) = withContext(Dispatchers.IO) {
        deleteNoteArtifacts(note.id)
        notes.delete(note)
    }

    suspend fun getAllForBackup(): List<Note> = withContext(Dispatchers.IO) { notes.getAllForBackup() }

    // ---- v2: photos / checklist / duplicate / trash auto-clean ----

    fun observeImages(noteId: Long): Flow<List<NoteImage>> = extras.observeImages(noteId)

    fun observeImageCounts(): Flow<List<ImageCount>> = extras.observeImageCounts()

    suspend fun getImages(noteId: Long): List<NoteImage> = withContext(Dispatchers.IO) { extras.imagesFor(noteId) }

    /** Records an already-saved photo file for a note (see NoteImageStore). */
    suspend fun addImageRecord(noteId: Long, fileName: String): Long = withContext(Dispatchers.IO) {
        extras.insertImage(NoteImage(noteId = noteId, fileName = fileName))
    }

    suspend fun removeImage(image: NoteImage) = withContext(Dispatchers.IO) {
        extras.deleteImage(image.id)
        NoteImageStore.file(appContext, image.fileName).delete()
    }

    suspend fun getChecklistItems(noteId: Long): List<ChecklistItem> = withContext(Dispatchers.IO) {
        extras.itemsFor(noteId)
    }

    /** Replaces all checklist rows of a note with the editor's current rows. */
    suspend fun replaceChecklistItems(noteId: Long, items: List<ChecklistItem>) = withContext(Dispatchers.IO) {
        extras.deleteItemsFor(noteId)
        items.forEachIndexed { index, item ->
            extras.insertItem(item.copy(id = 0, noteId = noteId, position = index))
        }
    }

    suspend fun clearChecklistItems(noteId: Long) = withContext(Dispatchers.IO) {
        extras.deleteItemsFor(noteId)
    }

    /**
     * Full copy of a note: same title + " (Copy)", category, pin,
     * color and checklist rows; photo files are copied byte-for-byte.
     * The copy is fresh (not archived/trashed) and its reminder is NOT
     * copied, so the user never gets surprise double notifications.
     */
    suspend fun duplicateNote(source: Note): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val newId = notes.insert(
            source.copy(
                id = 0,
                title = if (source.title.isBlank()) source.title else source.title + " (Copy)",
                archived = false,
                deletedAt = null,
                reminderAt = null,
                createdAt = now,
                updatedAt = now
            )
        )
        // Checklist rows
        extras.itemsFor(source.id).forEach { item ->
            extras.insertItem(item.copy(id = 0, noteId = newId))
        }
        // Photo files
        extras.imagesFor(source.id).forEach { image ->
            val src = NoteImageStore.file(appContext, image.fileName)
            if (src.exists()) {
                val newName = NoteImageStore.newFileName()
                val dst = NoteImageStore.file(appContext, newName)
                runCatching { src.copyTo(dst, overwrite = false) }.onSuccess {
                    extras.insertImage(NoteImage(noteId = newId, fileName = newName))
                }
            }
        }
        newId
    }

    /** Notes with a reminder set (for alarm (re)scheduling). */
    suspend fun getNotesWithReminders(): List<Note> = withContext(Dispatchers.IO) {
        notes.getNotesWithReminders()
    }

    /** Clears a fired one-shot reminder (list order/updatedAt untouched). */
    suspend fun clearReminder(noteId: Long) = withContext(Dispatchers.IO) {
        notes.clearReminder(noteId)
    }

    /**
     * Permanently deletes Trash entries older than 30 days, including
     * their photo files and checklist rows. Only Notes data.
     * Returns how many notes were removed.
     */
    suspend fun autoCleanTrash(): Int = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - TRASH_RETENTION_MS
        val old = notes.getTrashedBefore(cutoff)
        old.forEach { note ->
            deleteNoteArtifacts(note.id)
            notes.delete(note)
        }
        old.size
    }

    /** Removes photo files/rows and checklist rows belonging to a note. */
    private suspend fun deleteNoteArtifacts(noteId: Long) {
        extras.imagesFor(noteId).forEach { image ->
            NoteImageStore.file(appContext, image.fileName).delete()
        }
        extras.deleteImagesFor(noteId)
        extras.deleteItemsFor(noteId)
    }

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
