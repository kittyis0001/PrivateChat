package com.privatechat.app.notes.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Dedicated Notes database. Completely separate from the chat app's
 * Firebase Realtime Database; nothing in the chat system reads or
 * writes this file.
 *
 * v2 adds per-note color/checklist/reminder columns plus photo and
 * checklist-item tables. The migration is purely additive — no
 * existing row, column, or user note is touched or dropped.
 */
@Database(
    entities = [Note::class, Category::class, StickyNote::class, NoteImage::class, ChecklistItem::class],
    version = 2,
    exportSchema = false
)
abstract class NotesDatabase : RoomDatabase() {
    abstract fun notesDao(): NotesDao
    abstract fun categoryDao(): CategoryDao
    abstract fun stickyDao(): StickyDao
    abstract fun noteExtrasDao(): NoteExtrasDao

    companion object {
        @Volatile
        private var instance: NotesDatabase? = null

        /** Additive v1 -> v2 migration (new columns get their entity defaults). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN color TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE notes ADD COLUMN isChecklist INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN reminderAt INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS note_images (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "noteId INTEGER NOT NULL, " +
                        "fileName TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS note_checklist_items (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "noteId INTEGER NOT NULL, " +
                        "text TEXT NOT NULL, " +
                        "checked INTEGER NOT NULL, " +
                        "position INTEGER NOT NULL)"
                )
            }
        }

        fun get(context: Context): NotesDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NotesDatabase::class.java,
                    "kitty_notes.db"
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
        }
    }
}
