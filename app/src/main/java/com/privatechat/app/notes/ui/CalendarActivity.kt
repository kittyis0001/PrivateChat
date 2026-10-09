package com.privatechat.app.notes.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.GridLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.privatechat.app.R
import com.privatechat.app.notes.data.Note
import com.privatechat.app.notes.data.NotesRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Notes calendar. Dates are based on each note's last-edited date
 * (updatedAt), used consistently across this screen. Self-contained:
 * no device calendar integration and no calendar permissions.
 */
class CalendarActivity : AppCompatActivity() {

    private val repo by lazy { NotesRepository(this) }

    private lateinit var monthTitle: TextView
    private lateinit var grid: GridLayout
    private lateinit var dayTitle: TextView
    private lateinit var dayNotes: RecyclerView

    private val visibleMonth = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    private var selectedDayStart: Long = startOfDay(System.currentTimeMillis())
    private var allNotes: List<Note> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calendar)

        monthTitle = findViewById(R.id.calendarMonthTitle)
        grid = findViewById(R.id.calendarGrid)
        dayTitle = findViewById(R.id.calendarDayTitle)
        dayNotes = findViewById(R.id.calendarDayNotes)
        dayNotes.layoutManager = LinearLayoutManager(this)

        findViewById<View>(R.id.calendarBack).setOnClickListener { finish() }
        findViewById<View>(R.id.calendarPrev).setOnClickListener { visibleMonth.add(Calendar.MONTH, -1); render() }
        findViewById<View>(R.id.calendarNext).setOnClickListener { visibleMonth.add(Calendar.MONTH, 1); render() }
        findViewById<View>(R.id.calendarAddNote).setOnClickListener {
            startActivity(Intent(this, NoteEditorActivity::class.java))
        }

        lifecycleScope.launch {
            repo.observeActive().collectLatest { notes ->
                allNotes = notes
                render()
            }
        }
    }

    private fun startOfDay(millis: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun render() {
        monthTitle.text = MONTH_FMT.format(visibleMonth.time)
        grid.removeAllViews()

        val firstDow = visibleMonth.get(Calendar.DAY_OF_WEEK) // 1=Sun
        val daysInMonth = visibleMonth.getActualMaximum(Calendar.DAY_OF_MONTH)
        val noteDays = allNotes.map { startOfDay(it.updatedAt) }.toSet()

        // Weekday header
        val headers = listOf("S", "M", "T", "W", "T", "F", "S")
        headers.forEach { h ->
            grid.addView(cell(h, bold = true, hasNote = false, isSelected = false, muted = false) {})
        }
        // Leading blanks
        repeat(firstDow - 1) { grid.addView(cell("", bold = false, hasNote = false, isSelected = false, muted = true) {}) }
        for (day in 1..daysInMonth) {
            val cellCal = (visibleMonth.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, day) }
            val dayStart = startOfDay(cellCal.timeInMillis)
            val label = if (noteDays.contains(dayStart)) "$day •" else day.toString()
            grid.addView(cell(label, bold = false, hasNote = noteDays.contains(dayStart), isSelected = dayStart == selectedDayStart, muted = false) {
                selectedDayStart = dayStart
                render()
            })
        }
        renderDayNotes()
    }

    private fun cell(text: String, bold: Boolean, hasNote: Boolean, isSelected: Boolean, muted: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setPadding(4, 14, 4, 14)
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            }
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            if (muted) setTextColor(getColor(R.color.textSecondary)) else setTextColor(getColor(R.color.textPrimary))
            if (isSelected) setBackgroundResource(R.drawable.bg_chip_selected)
            if (isSelected) setTextColor(getColor(R.color.kittyOnPrimary))
            if (text.isNotEmpty() && !bold) setOnClickListener { onClick() }
        }
    }

    private fun renderDayNotes() {
        dayTitle.text = getString(R.string.notes_calendar_day_notes, DAY_FMT.format(Date(selectedDayStart)))
        val dayEnd = selectedDayStart + 24L * 60 * 60 * 1000
        val dayList = allNotes.filter { it.updatedAt in selectedDayStart until dayEnd }
        val adapter = SimpleNotesAdapter { note ->
            startActivity(Intent(this, NoteEditorActivity::class.java).putExtra(NoteEditorActivity.EXTRA_NOTE_ID, note.id))
        }
        dayNotes.adapter = adapter
        adapter.submitList(dayList)
    }

    companion object {
        private val MONTH_FMT = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        private val DAY_FMT = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
    }
}
