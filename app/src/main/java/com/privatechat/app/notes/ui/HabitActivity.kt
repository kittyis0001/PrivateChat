package com.privatechat.app.notes.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.privatechat.app.R
import com.privatechat.app.notes.data.Habit
import com.privatechat.app.notes.data.HabitTick
import com.privatechat.app.notes.data.NotesRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Calendar

/** Simple daily habit tracker: add a habit, tick it each day, see the streak. */
class HabitActivity : AppCompatActivity() {

    private lateinit var repo: NotesRepository
    private val tickMap = mutableMapOf<Long, List<HabitTick>>()
    private var tickJobs = listOf<Job>()
    private lateinit var adapter: HabitAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_habit)
        repo = NotesRepository(this)
        findViewById<View>(R.id.habitBack).setOnClickListener { finish() }
        val list = findViewById<RecyclerView>(R.id.habitList)
        list.layoutManager = LinearLayoutManager(this)
        adapter = HabitAdapter(
            onToggle = { habit ->
                lifecycleScope.launch {
                    repo.toggleTick(habit.id, dayStart(System.currentTimeMillis()))
                }
            },
            onDelete = { habit ->
                lifecycleScope.launch { repo.deleteHabit(habit) }
            },
            ticksFor = { habitId -> tickMap[habitId] ?: emptyList() }
        )
        list.adapter = adapter
        findViewById<Button>(R.id.habitAddButton).setOnClickListener {
            val input = findViewById<EditText>(R.id.habitNameInput)
            val name = input.text.toString().trim()
            if (name.isEmpty()) return@setOnClickListener
            lifecycleScope.launch {
                repo.saveHabit(Habit(name = name))
                input.setText("")
            }
        }
        lifecycleScope.launch {
            repo.observeHabits().collectLatest { habits ->
                tickJobs.forEach { it.cancel() }
                tickJobs = habits.map { habit ->
                    lifecycleScope.launch {
                        repo.observeTicks(habit.id).collect { ticks ->
                            tickMap[habit.id] = ticks
                            adapter.notifyDataSetChanged()
                        }
                    }
                }
                findViewById<TextView>(R.id.habitEmpty).visibility =
                    if (habits.isEmpty()) View.VISIBLE else View.GONE
                adapter.submit(habits)
            }
        }
    }

    override fun onDestroy() {
        tickJobs.forEach { it.cancel() }
        super.onDestroy()
    }

    private class HabitAdapter(
        private val onToggle: (Habit) -> Unit,
        private val onDelete: (Habit) -> Unit,
        private val ticksFor: (Long) -> List<HabitTick>
    ) : RecyclerView.Adapter<HabitAdapter.Holder>() {

        private var items = listOf<Habit>()

        fun submit(list: List<Habit>) {
            items = list
            notifyDataSetChanged()
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.habitRowName)
            val streak: TextView = view.findViewById(R.id.habitRowStreak)
            val today: CheckBox = view.findViewById(R.id.habitRowToday)
            val delete: TextView = view.findViewById(R.id.habitRowDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_habit, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val habit = items[position]
            holder.name.text = habit.name
            val ticks = ticksFor(habit.id)
            val today = dayStart(System.currentTimeMillis())
            holder.today.isChecked = ticks.any { it.dayStartMs == today && it.done }
            val streak = streakDays(ticks)
            holder.streak.text = if (streak > 0) {
                holder.itemView.context.getString(R.string.notes_habit_streak, streak)
            } else {
                ""
            }
            holder.today.setOnClickListener { onToggle(habit) }
            holder.delete.setOnClickListener { onDelete(habit) }
        }

        override fun getItemCount(): Int = items.size
    }

    companion object {
        fun dayStart(millis: Long): Long {
            val cal = Calendar.getInstance().apply { timeInMillis = millis }
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        /** Consecutive ticked days ending today (or yesterday). */
        fun streakDays(ticks: List<HabitTick>): Int {
            val days = ticks.filter { it.done }.map { it.dayStartMs }.toSet()
            if (days.isEmpty()) return 0
            var day = dayStart(System.currentTimeMillis())
            if (!days.contains(day)) {
                day -= 24L * 60 * 60 * 1000
                if (!days.contains(day)) return 0
            }
            var count = 0
            while (days.contains(day)) {
                count++
                day -= 24L * 60 * 60 * 1000
            }
            return count
        }
    }
}
