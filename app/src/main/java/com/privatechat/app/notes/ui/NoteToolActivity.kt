package com.privatechat.app.notes.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.privatechat.app.R

/** Small working text tools for notes: word/character count, checklist, copy/share. */
class NoteToolActivity : AppCompatActivity() {

    private lateinit var input: EditText
    private lateinit var stats: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_note_tool)

        input = findViewById(R.id.toolInput)
        stats = findViewById(R.id.toolStats)

        findViewById<View>(R.id.toolBack).setOnClickListener { finish() }

        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { updateStats() }
        })

        findViewById<View>(R.id.toolCopy).setOnClickListener {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("text", input.text.toString()))
            Toast.makeText(this, getString(R.string.notes_copied), Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.toolShare).setOnClickListener {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, input.text.toString())
            }
            startActivity(Intent.createChooser(intent, getString(R.string.notes_share_note)))
        }
        findViewById<View>(R.id.toolChecklist).setOnClickListener {
            val lines = input.text.toString().lines()
            val out = lines.joinToString("\n") { line ->
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("- [")) line else "- [ ] $t"
            }
            input.setText(out)
            input.setSelection(input.text.length)
        }
        findViewById<View>(R.id.toolClear).setOnClickListener { input.text.clear() }
        updateStats()
    }

    private fun updateStats() {
        val text = input.text.toString()
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
        val chars = text.length
        val noSpaces = text.replace(" ", "").replace("\n", "").length
        stats.text = getString(R.string.notes_tool_stats, words, chars, noSpaces)
    }
}
