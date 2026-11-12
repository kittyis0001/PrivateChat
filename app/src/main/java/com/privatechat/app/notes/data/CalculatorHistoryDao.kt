package com.privatechat.app.notes.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface CalculatorHistoryDao {

    @Insert
    suspend fun insert(entry: CalculatorHistory): Long

    @Query("SELECT * FROM calculator_history ORDER BY createdAt DESC, id DESC")
    suspend fun getAll(): List<CalculatorHistory>

    @Query("DELETE FROM calculator_history")
    suspend fun deleteAll()

    /** Keeps only the newest [limit] entries, so history stays light. */
    @Query(
        "DELETE FROM calculator_history WHERE id NOT IN (" +
            "SELECT id FROM calculator_history ORDER BY createdAt DESC, id DESC LIMIT :limit)"
    )
    suspend fun trimTo(limit: Int)
}
