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

/** Used by Archive and Trash screens. */
class SimpleNotesAdapter(
    private val onClick: (Note) -> Unit
) : ListAdapter<Note, SimpleNotesAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_note, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val note = getItem(position)
        holder.title.text = note.title.ifBlank { holder.itemView.context.getString(R.string.notes_untitled) }
        holder.date.text = DATE_FMT.format(Date(note.updatedAt))
        holder.pin.visibility = if (note.pinned) View.VISIBLE else View.GONE
        NoteCardStyle.bind(holder.itemView, holder.title, holder.preview, holder.date, note)
        holder.itemView.setOnClickListener { onClick(note) }
        // Study pack: a locked note never shows its content preview,
        // not even in Archive / Trash — only the 🔒 badge.
        val ctx = holder.itemView.context
        holder.lock.visibility = if (note.locked) View.VISIBLE else View.GONE
        if (note.locked) {
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
        val pin: TextView = v.findViewById(R.id.notePin)
        val lock: TextView = v.findViewById(R.id.noteLock)
    }

    companion object {
        private val DATE_FMT = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault())
        private val DIFF = object : DiffUtil.ItemCallback<Note>() {
            override fun areItemsTheSame(a: Note, b: Note) = a.id == b.id
            override fun areContentsTheSame(a: Note, b: Note) = a == b
        }
    }
}
