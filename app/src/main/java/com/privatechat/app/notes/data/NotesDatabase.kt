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
 * v3 adds a reminderRepeat column plus habit and voice-note tables.
 * v4 adds a tags column.
 * v5 adds locked and expiresAt columns (per-note lock, self-destruct).
 * v6 adds a countdownAt column (countdown note).
 * v7 adds ai_chats and ai_chat_messages tables (saved AI chats).
 * v8 adds a calculator_history table (calculator history).
 */
@Database(
    entities = [Note::class, Category::class, StickyNote::class, NoteImage::class, ChecklistItem::class, Habit::class, HabitTick::class, VoiceNote::class, AiChat::class, AiChatMessageEntity::class, CalculatorHistory::class],
    version = 8,
    exportSchema = false
)
abstract class NotesDatabase : RoomDatabase() {
    abstract fun notesDao(): NotesDao
    abstract fun categoryDao(): CategoryDao
    abstract fun stickyDao(): StickyDao
    abstract fun noteExtrasDao(): NoteExtrasDao
    abstract fun habitDao(): HabitDao
    abstract fun voiceNoteDao(): VoiceNoteDao
    abstract fun aiChatDao(): AiChatDao
    abstract fun calculatorHistoryDao(): CalculatorHistoryDao

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

        /** Additive v2 -> v3 migration (new tables + one new column). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN reminderRepeat TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS habits (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "color TEXT NOT NULL, " +
                        "targetPerDay INTEGER NOT NULL, " +
                        "createdAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS habit_ticks (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "habitId INTEGER NOT NULL, " +
                        "dayStartMs INTEGER NOT NULL, " +
                        "done INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_habit_ticks_habitId_dayStartMs " +
                        "ON habit_ticks (habitId, dayStartMs)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS voice_notes (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "noteId INTEGER NOT NULL, " +
                        "fileName TEXT NOT NULL, " +
                        "durationMs INTEGER NOT NULL, " +
                        "transcript TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL)"
                )
            }
        }

        /** Additive v3 -> v4 migration (one new column). */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Additive v4 -> v5 migration (two new columns). */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN locked INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN expiresAt INTEGER")
            }
        }

        /** Additive v5 -> v6 migration (one new column). */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN countdownAt INTEGER")
            }
        }

        /** Additive v6 -> v7 migration (saved AI chats: two new tables). */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS ai_chats (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "title TEXT NOT NULL, " +
                        "contextText TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS ai_chat_messages (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "chatId INTEGER NOT NULL, " +
                        "role TEXT NOT NULL, " +
                        "text TEXT NOT NULL, " +
                        "image BLOB, " +
                        "createdAt INTEGER NOT NULL)"
                )
            }
        }

        /** Additive v7 -> v8 migration (calculator history: one new table). */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS calculator_history (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "expression TEXT NOT NULL, " +
                        "result TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL)"
                )
            }
        }

        fun get(context: Context): NotesDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NotesDatabase::class.java,
                    "kitty_notes.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).build().also { instance = it }
            }
        }
    }
}
