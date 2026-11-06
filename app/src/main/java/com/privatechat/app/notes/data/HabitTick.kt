package com.privatechat.app.notes.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One day's tick for a habit. dayStartMs is midnight of that day. */
@Entity(
    tableName = "habit_ticks",
    indices = [Index(value = ["habitId", "dayStartMs"], unique = true)]
)
data class HabitTick(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val habitId: Long,
    val dayStartMs: Long,
    val done: Boolean = true
)
