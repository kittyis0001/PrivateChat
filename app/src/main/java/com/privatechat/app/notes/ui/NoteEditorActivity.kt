package com.privatechat.app.notes.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.privatechat.app.R
import com.privatechat.app.notes.data.Category
import com.privatechat.app.notes.data.Note
import com.privatechat.app.notes.data.NotesRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Create/edit a single note. Auto-saves with a debounce and always
 * saves the latest state when leaving, without ever creating a
 * duplicate for the same editing session.
 */
class NoteEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NOTE_ID = "note_id"
        const val EXTRA_TEMPLATE_TITLE = "template_title"
        const val EXTRA_TEMPLATE_CONTENT = "template_content"
        private val DATE_FMT = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault())
    }

    private val repo by lazy { NotesRepository(this) }

    private lateinit var titleInput: EditText
    private lateinit var contentInput: EditText
    private lateinit var categorySpinner: Spinner
    private lateinit var metaText: TextView

    private var noteId: Long = 0
    private var categories: List<Category> = emptyList()
    private var pinned: Boolean = false
    private var createdAt: Long = System.currentTimeMillis()
    private var saveJob: Job? = null
    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_note_editor)

        titleInput = findViewById(R.id.editorTitle)
        contentInput = findViewById(R.id.editorContent)
        categorySpinner = findViewById(R.id.editorCategory)
        metaText = findViewById(R.id.editorMeta)

        findViewById<View>(R.id.editorBack).setOnClickListener { saveAndFinish() }
        findViewById<View>(R.id.editorSave).setOnClickListener { saveAndFinish() }
        findViewById<View>(R.id.editorPin).setOnClickListener {
            pinned = !pinned
            Toast.makeText(this, if (pinned) getString(R.string.notes_pinned) else getString(R.string.notes_unpinned), Toast.LENGTH_SHORT).show()
            scheduleSave()
        }
        findViewById<View>(R.id.editorShare).setOnClickListener { shareNote() }
        findViewById<View>(R.id.editorCopy).setOnClickListener {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("note", buildShareText()))
            Toast.makeText(this, getString(R.string.notes_copied), Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.editorDelete).setOnClickListener { confirmTrash() }

        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { if (loaded) scheduleSave() }
        }
        titleInput.addTextChangedListener(watcher)
        contentInput.addTextChangedListener(watcher)
        categorySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { if (loaded) scheduleSave() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        noteId = intent.getLongExtra(EXTRA_NOTE_ID, 0)
        lifecycleScope.launch {
            categories = repo.observeCategories().first()
            val names = listOf(getString(R.string.notes_no_tag)) + categories.map { it.name }
            categorySpinner.adapter = ArrayAdapter(this@NoteEditorActivity, android.R.layout.simple_spinner_dropdown_item, names)

            if (noteId != 0L) {
                repo.getNote(noteId)?.let { n ->
                    titleInput.setText(n.title)
                    contentInput.setText(n.content)
                    pinned = n.pinned
                    createdAt = n.createdAt
                    val idx = if (n.categoryId == null) 0 else categories.indexOfFirst { it.id == n.categoryId } + 1
                    if (idx in names.indices) categorySpinner.setSelection(idx)
                    metaText.text = getString(R.string.notes_last_edited, DATE_FMT.format(Date(n.updatedAt)))
                }
            } else {
                intent.getStringExtra(EXTRA_TEMPLATE_TITLE)?.let { titleInput.setText(it) }
                intent.getStringExtra(EXTRA_TEMPLATE_CONTENT)?.let { contentInput.setText(it) }
                metaText.text = getString(R.string.notes_new_note)
            }
            loaded = true
        }
    }

    private fun selectedCategoryId(): Long? {
        val pos = categorySpinner.selectedItemPosition
        return if (pos <= 0) null else categories.getOrNull(pos - 1)?.id
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = lifecycleScope.launch {
            delay(800)
            persist(showToast = false)
        }
    }

    private suspend fun persistNow(): Boolean {
        val title = titleInput.text.toString()
        val content = contentInput.text.toString()
        if (title.isBlank() && content.isBlank()) return false
        val note = Note(
            id = noteId,
            title = title.trim(),
            content = content,
            categoryId = selectedCategoryId(),
            pinned = pinned,
            createdAt = createdAt,
            updatedAt = System.currentTimeMillis()
        )
        noteId = repo.saveNote(note)
        return true
    }

    private fun persist(showToast: Boolean) {
        lifecycleScope.launch {
            if (persistNow() && showToast) {
                Toast.makeText(this@NoteEditorActivity, getString(R.string.notes_saved), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveAndFinish() {
        saveJob?.cancel()
        lifecycleScope.launch {
            persistNow()
            finish()
        }
    }

    private fun buildShareText(): String {
        val t = titleInput.text.toString().trim()
        val c = contentInput.text.toString().trim()
        return if (t.isEmpty()) c else "$t\n\n$c"
    }

    private fun shareNote() {
        val text = buildShareText()
        if (text.isBlank()) {
            Toast.makeText(this, getString(R.string.notes_nothing_to_share), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, titleInput.text.toString().trim())
        }
        startActivity(Intent.createChooser(intent, getString(R.string.notes_share_note)))
    }

    private fun confirmTrash() {
        if (noteId == 0L) { finish(); return }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.notes_move_to_trash_title))
            .setMessage(getString(R.string.notes_move_to_trash_msg))
            .setPositiveButton(getString(R.string.notes_move_to_trash)) { _, _ ->
                lifecycleScope.launch {
                    repo.getNote(noteId)?.let { repo.moveToTrash(it) }
                    finish()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        // Final safety save when leaving; persist() is a no-op for empty notes.
        if (loaded) persist(showToast = false)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        saveAndFinish()
    }

}
