package com.privatechat.app.notes.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A daily habit the user ticks off (e.g. reading, drinking water). */
@Entity(tableName = "habits")
data class Habit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: String = "",
    val targetPerDay: Int = 1,
    val createdAt: Long = System.currentTimeMillis()
)
