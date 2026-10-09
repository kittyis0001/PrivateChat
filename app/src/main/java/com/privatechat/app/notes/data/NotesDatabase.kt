package com.privatechat.app.notes.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Dedicated Notes database. Completely separate from the chat app's
 * Firebase Realtime Database; nothing in the chat system reads or
 * writes this file.
 */
@Database(
    entities = [Note::class, Category::class, StickyNote::class],
    version = 1,
    exportSchema = false
)
abstract class NotesDatabase : RoomDatabase() {
    abstract fun notesDao(): NotesDao
    abstract fun categoryDao(): CategoryDao
    abstract fun stickyDao(): StickyDao

    companion object {
        @Volatile
        private var instance: NotesDatabase? = null

        fun get(context: Context): NotesDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NotesDatabase::class.java,
                    "kitty_notes.db"
                ).build().also { instance = it }
            }
        }
    }
}
