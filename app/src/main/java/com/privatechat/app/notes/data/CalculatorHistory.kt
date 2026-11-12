package com.privatechat.app.notes.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One calculator history entry. The expression is stored as shown
 * (grouped numbers, e.g. "3,290×15%") and the result as shown
 * (e.g. "493.5" or "Error"). Notes-only: chat data is never touched.
 */
@Entity(tableName = "calculator_history")
data class CalculatorHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val expression: String,
    val result: String,
    val createdAt: Long = System.currentTimeMillis()
)
