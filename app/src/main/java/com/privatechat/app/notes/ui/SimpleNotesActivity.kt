package com.privatechat.app.notes.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.privatechat.app.R
import com.privatechat.app.notes.data.Note
import com.privatechat.app.notes.data.NotesRepository
import com.privatechat.app.notes.reminder.ReminderManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Archive and Trash lists. Trash deletes are permanent; nothing here touches chat data. */
class SimpleNotesActivity : AppCompatActivity() {

    companion object {
        const val MODE_ARCHIVE = "archive"
        const val MODE_TRASH = "trash"
        private const val EXTRA_MODE = "mode"

        fun intent(context: Context, mode: String): Intent =
            Intent(context, SimpleNotesActivity::class.java).putExtra(EXTRA_MODE, mode)
    }

    private val repo by lazy { NotesRepository(this) }
    private var mode: String = MODE_ARCHIVE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notes_list)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_ARCHIVE

        findViewById<TextView>(R.id.listTitle).text =
            if (mode == MODE_ARCHIVE) getString(R.string.notes_archive_title) else getString(R.string.notes_trash_title)
        findViewById<View>(R.id.listBack).setOnClickListener { finish() }

        val list = findViewById<RecyclerView>(R.id.simpleNotesList)
        val empty = findViewById<TextView>(R.id.simpleNotesEmpty)
        empty.text = if (mode == MODE_ARCHIVE) getString(R.string.notes_archive_empty) else getString(R.string.notes_trash_empty)
        val adapter = SimpleNotesAdapter { note -> showActions(note) }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        lifecycleScope.launch {
            val flow = if (mode == MODE_ARCHIVE) repo.observeArchived() else repo.observeTrash()
            flow.collectLatest { notes ->
                adapter.submitList(notes)
                empty.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showActions(note: Note) {
        if (mode == MODE_ARCHIVE) {
            AlertDialog.Builder(this)
                .setTitle(note.title.ifBlank { getString(R.string.notes_untitled) })
                .setItems(arrayOf(getString(R.string.notes_restore), getString(R.string.notes_delete_forever))) { _, which ->
                    lifecycleScope.launch {
                        if (which == 0) {
                            repo.setArchived(note, false)
                            ReminderManager.rescheduleAll(this@SimpleNotesActivity)
                        } else {
                            repo.deleteForever(note)
                            ReminderManager.cancel(this@SimpleNotesActivity, note.id)
                        }
                    }
                }
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle(note.title.ifBlank { getString(R.string.notes_untitled) })
                .setItems(arrayOf(getString(R.string.notes_restore), getString(R.string.notes_delete_forever))) { _, which ->
                    if (which == 0) {
                        lifecycleScope.launch {
                            repo.restoreFromTrash(note)
                            ReminderManager.rescheduleAll(this@SimpleNotesActivity)
                        }
                    } else {
                        confirmPermanentDelete(note)
                    }
                }
                .show()
        }
    }

    private fun confirmPermanentDelete(note: Note) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.notes_delete_forever_title))
            .setMessage(getString(R.string.notes_delete_forever_msg))
            .setPositiveButton(getString(R.string.notes_delete)) { _, _ ->
                lifecycleScope.launch {
                    repo.deleteForever(note)
                    ReminderManager.cancel(this@SimpleNotesActivity, note.id)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
