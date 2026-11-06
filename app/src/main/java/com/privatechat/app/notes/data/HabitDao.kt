package com.privatechat.app.notes.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface HabitDao {
    @Query("SELECT * FROM habits ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<Habit>>

    @Insert
    suspend fun insert(habit: Habit): Long

    @Update
    suspend fun update(habit: Habit)

    @Delete
    suspend fun delete(habit: Habit)

    @Query("SELECT * FROM habit_ticks WHERE habitId = :habitId ORDER BY dayStartMs DESC")
    fun observeTicks(habitId: Long): Flow<List<HabitTick>>

    @Query("SELECT * FROM habit_ticks WHERE habitId = :habitId AND dayStartMs = :dayStartMs")
    suspend fun tickForDay(habitId: Long, dayStartMs: Long): HabitTick?

    @Insert
    suspend fun insertTick(tick: HabitTick): Long

    @Query("DELETE FROM habit_ticks WHERE habitId = :habitId AND dayStartMs = :dayStartMs")
    suspend fun deleteTick(habitId: Long, dayStartMs: Long)

    @Query("DELETE FROM habit_ticks WHERE habitId = :habitId")
    suspend fun deleteTicksForHabit(habitId: Long)
}
