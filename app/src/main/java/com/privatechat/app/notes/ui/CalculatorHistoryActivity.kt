package com.privatechat.app.notes.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.privatechat.app.R
import com.privatechat.app.notes.data.CalculatorHistory
import com.privatechat.app.notes.data.NotesRepository
import com.privatechat.app.notes.sync.CalculatorHistorySync
import com.privatechat.app.notes.sync.NotesSyncManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Calculator history, grouped by date (newest first) like the
 * reference screenshots. Tapping an entry sends its expression back
 * to the calculator; the trash icon clears everything (with a
 * confirm dialog, and the empty list is pushed to the cloud too).
 */
class CalculatorHistoryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_EXPRESSION = "expression"
    }

    private lateinit var repo: NotesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calculator_history)
        repo = NotesRepository(this)

        findViewById<View>(R.id.calcHistoryBack).setOnClickListener { finish() }
        findViewById<View>(R.id.calcHistoryClear).setOnClickListener { confirmClear() }
        loadHistory()
    }

    private fun loadHistory() {
        lifecycleScope.launch {
            render(repo.getCalculatorHistory())
        }
    }

    private fun render(entries: List<CalculatorHistory>) {
        val container = findViewById<LinearLayout>(R.id.calcHistoryList)
        val empty = findViewById<View>(R.id.calcHistoryEmpty)
        container.removeAllViews()
        if (entries.isEmpty()) {
            empty.visibility = View.VISIBLE
            return
        }
        empty.visibility = View.GONE
        val dateFmt = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        var lastDate = ""
        entries.forEach { entry ->
            val date = dateFmt.format(Date(entry.createdAt))
            if (date != lastDate) {
                lastDate = date
                container.addView(dateHeader(date))
                container.addView(divider())
            }
            container.addView(entryView(entry))
        }
    }

    private fun dateHeader(date: String): TextView {
        return TextView(this).apply {
            text = date
            setTextColor(0xFF8E8E93.toInt())
            textSize = 14f
            gravity = Gravity.END
            setPadding(0, 28, 0, 12)
        }
    }

    private fun divider(): View {
        return View(this).apply {
            setBackgroundColor(0xFF262626.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            )
        }
    }

    private fun entryView(entry: CalculatorHistory): TextView {
        return TextView(this).apply {
            text = "${entry.expression}= ${entry.result}"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 22f
            gravity = Gravity.END
            setPadding(0, 20, 0, 20)
            setOnClickListener {
                setResult(
                    RESULT_OK,
                    Intent().putExtra(EXTRA_EXPRESSION, entry.expression)
                )
                finish()
            }
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.calculator_clear_history))
            .setMessage(getString(R.string.calculator_clear_history_confirm))
            .setPositiveButton(getString(R.string.notes_delete_short)) { _, _ ->
                lifecycleScope.launch {
                    repo.clearCalculatorHistory()
                    if (NotesSyncManager.isSignedIn()) {
                        CalculatorHistorySync.uploadNow(repo)
                    }
                    render(emptyList())
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
