package com.privatechat.app.notes.util

import android.graphics.Typeface
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan

/**
 * Bold text in a plain-text note, stored as **markers**.
 *
 * Notes are stored as plain strings (Note.content), so there is
 * nowhere to keep a font weight except inside the text itself.
 * The convention is Markdown-style: **word** in storage means the
 * word is bold. The editor shows it bold (markers hidden) and
 * converts it back to markers when saving, so the round-trip is
 * stable: load -> bold spans -> save -> the same **markers**.
 * PDF export and Share/Save as image render the same markers bold.
 * A lone "**" with no closing pair stays literal text.
 */
object RichText {

    private val BOLD_PAIR = Regex("\\*\\*[^*]+\\*\\*")

    fun hasBoldMarkers(text: String): Boolean = BOLD_PAIR.containsMatchIn(text)

    /** "**word**" -> bold "word"; markers are removed from the display text. */
    fun toSpanned(text: String): SpannableStringBuilder {
        val out = SpannableStringBuilder()
        var i = 0
        while (i < text.length) {
            val open = text.indexOf("**", i)
            if (open < 0) {
                out.append(text.substring(i))
                break
            }
            val close = text.indexOf("**", open + 2)
            if (close < 0 || close == open + 2) {
                // No closing pair (or an empty one): keep it literal.
                out.append(text.substring(i, open + 2))
                i = open + 2
                continue
            }
            out.append(text.substring(i, open))
            val start = out.length
            out.append(text.substring(open + 2, close))
            out.setSpan(
                StyleSpan(Typeface.BOLD),
                start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            i = close + 2
        }
        return out
    }

    /** Bold spans in the editor -> "**word**" markers for storage. */
    fun toMarkdown(editable: Editable): String {
        val spans = editable.getSpans(0, editable.length, StyleSpan::class.java)
            .filter { it.style == Typeface.BOLD || it.style == Typeface.BOLD_ITALIC }
            .map { editable.getSpanStart(it) to editable.getSpanEnd(it) }
            .filter { (start, end) -> start >= 0 && end > start }
            .sortedBy { it.first }
        if (spans.isEmpty()) return editable.toString()
        val out = StringBuilder()
        var pos = 0
        spans.forEach { (start, end) ->
            if (start < pos) return@forEach // overlapping span: skip
            out.append(editable.subSequence(pos, start))
            out.append("**").append(editable.subSequence(start, end)).append("**")
            pos = end
        }
        out.append(editable.subSequence(pos, editable.length))
        return out.toString()
    }
}
