package com.privatechat.app.notes.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.privatechat.app.R
import com.privatechat.app.notes.data.Note

/**
 * Shared card styling for note lists. Uncolored notes render exactly
 * as before (theme surface + theme text colors); choosing a pastel
 * color tints the same card shape and switches to a dark text tone
 * that stays readable on pastels in both light and dark mode.
 */
object NoteCardStyle {

    fun bind(
        itemView: View,
        title: TextView,
        preview: TextView,
        date: TextView,
        note: Note,
        photoCount: Int = 0
    ) {
        val ctx = itemView.context
        val bg = itemView.background?.mutate() as? GradientDrawable
        if (note.color.isNotBlank()) {
            runCatching { bg?.setColor(Color.parseColor(note.color)) }
            val onColor = ContextCompat.getColor(ctx, R.color.noteOnColor)
            title.setTextColor(onColor)
            preview.setTextColor(onColor)
            date.setTextColor(onColor)
        } else {
            bg?.setColor(ContextCompat.getColor(ctx, R.color.surface))
            title.setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary))
            preview.setTextColor(ContextCompat.getColor(ctx, R.color.textSecondary))
            date.setTextColor(ContextCompat.getColor(ctx, R.color.textSecondary))
        }
        val base = note.content.replace("\n", " ").take(120)
        preview.text = if (photoCount > 0) "🖼 $photoCount · $base" else base
    }
}
