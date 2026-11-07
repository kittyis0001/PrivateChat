package com.privatechat.app.notes.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.privatechat.app.R
import com.privatechat.app.notes.data.Note
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotesAdapter(
    private val onClick: (Note) -> Unit,
    private val onLongClick: (Note) -> Unit
) : ListAdapter<Note, NotesAdapter.VH>(DIFF) {

    /** noteId -> photo count, for the small 🖼 badge (0/absent = no badge). */
    var imageCounts: Map<Long, Int> = emptyMap()

    /** Bulk select state, driven by NotesActivity. */
    var selectionMode: Boolean = false
    var selectedIds: Set<Long> = emptySet()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_note, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val note = getItem(position)
        holder.title.text = note.title.ifBlank { holder.itemView.context.getString(R.string.notes_untitled) }
        holder.date.text = DATE_FMT.format(Date(note.updatedAt))
        holder.pin.visibility = if (note.pinned) View.VISIBLE else View.GONE
        val tags = note.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        holder.tags.text = tags.joinToString(" ") { "#$it" }
        holder.tags.visibility = if (tags.isEmpty()) View.GONE else View.VISIBLE
        NoteCardStyle.bind(
            holder.itemView, holder.title, holder.preview, holder.date,
            note, imageCounts[note.id] ?: 0
        )
        holder.itemView.setOnClickListener { onClick(note) }
        holder.itemView.setOnLongClickListener { onLongClick(note); true }
        // Study pack: selection check, lock badge, hidden preview, expiry.
        val ctx = holder.itemView.context
        holder.selected.visibility =
            if (selectionMode && selectedIds.contains(note.id)) View.VISIBLE else View.GONE
        holder.lock.visibility = if (note.locked) View.VISIBLE else View.GONE
        if (note.locked) {
            // Locked: the content preview never shows on the card.
            holder.preview.text = ctx.getString(R.string.notes_locked_preview)
        }
        if (note.expiresAt != null) {
            val days = ((note.expiresAt - System.currentTimeMillis()) / 86_400_000L).toInt()
            val suffix = if (days >= 1) ctx.getString(R.string.notes_self_destruct_days_left, days)
            else ctx.getString(R.string.notes_self_destruct_today)
            holder.date.text = "${holder.date.text} · $suffix"
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.noteTitle)
        val preview: TextView = v.findViewById(R.id.notePreview)
        val date: TextView = v.findViewById(R.id.noteDate)
        val tags: TextView = v.findViewById(R.id.noteTags)
        val pin: TextView = v.findViewById(R.id.notePin)
        val lock: TextView = v.findViewById(R.id.noteLock)
        val selected: TextView = v.findViewById(R.id.noteSelected)
    }

    companion object {
        private val DATE_FMT = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault())
        private val DIFF = object : DiffUtil.ItemCallback<Note>() {
            override fun areItemsTheSame(a: Note, b: Note) = a.id == b.id
            override fun areContentsTheSame(a: Note, b: Note) = a == b
        }
    }
}
