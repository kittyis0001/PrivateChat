package com.privatechat.app.notes.util

import android.graphics.Typeface
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan

/**
 * Bold and underline in a plain-text note, stored as markers.
 *
 * Notes are stored as plain strings (Note.content), so there is
 * nowhere to keep a font weight or an underline except inside the
 * text itself. The convention is Markdown-style: **word** means
 * bold, __word__ means underlined, and **__word__** means both.
 * The editor shows them formatted (markers hidden) and converts
 * them back to markers when saving, so the round-trip is stable:
 * load -> spans -> save -> the same markers.
 * PDF export and Share/Save as image render the same markers,
 * because they draw [RichText.toSpanned] through a StaticLayout,
 * which honours both StyleSpan and UnderlineSpan.
 * A lone "**" or "__" with no closing pair stays literal text.
 *
 * The markers come from the scan flow: the on-device OCR marks
 * **bold** by line size (see [TextScanner]), and the Gemini vision
 * pass adds __underline__ and rebuilds tables when the user has
 * an AI key (see [GeminiSummarizer.fixScannedPage]).
 */
object RichText {

    private val BOLD_PAIR = Regex("\\*\\*[^*]+\\*\\*")
    private val UNDERLINE_PAIR = Regex("__[^_]+__")

    fun hasBoldMarkers(text: String): Boolean = BOLD_PAIR.containsMatchIn(text)

    fun hasUnderlineMarkers(text: String): Boolean = UNDERLINE_PAIR.containsMatchIn(text)

    fun hasMarkers(text: String): Boolean = hasBoldMarkers(text) || hasUnderlineMarkers(text)

    /** Index of the closing marker after [open], or -1 when there is none / it is empty. */
    private fun closingMarker(text: String, marker: String, open: Int): Int {
        val close = text.indexOf(marker, open + marker.length)
        return if (close < 0 || close == open + marker.length) -1 else close
    }

    /**
     * "**word**" -> bold "word", "__word__" -> underlined "word",
     * "**__word__**" / "__**word__**" -> both; markers are removed
     * from the display text.
     */
    fun toSpanned(text: String): SpannableStringBuilder {
        val out = SpannableStringBuilder()
        var i = 0
        while (i < text.length) {
            val boldOpen = text.indexOf("**", i)
            val underOpen = text.indexOf("__", i)
            if (boldOpen < 0 && underOpen < 0) {
                out.append(text.substring(i))
                break
            }
            val (open, bold) = if (underOpen < 0 || (boldOpen >= 0 && boldOpen < underOpen)) {
                boldOpen to true
            } else {
                underOpen to false
            }
            val marker = if (bold) "**" else "__"
            val close = closingMarker(text, marker, open)
            if (close < 0) {
                // No closing pair (or an empty one): keep it literal.
                out.append(text.substring(i, open + marker.length))
                i = open + marker.length
                continue
            }
            out.append(text.substring(i, open))
            var inner = text.substring(open + marker.length, close)
            var isBold = bold
            var isUnder = !bold
            // Nested other marker: **__word__** or __**word__**.
            val other = if (bold) "__" else "**"
            if (inner.length >= other.length * 2 + 1 &&
                inner.startsWith(other) && inner.endsWith(other)
            ) {
                inner = inner.substring(other.length, inner.length - other.length)
                isBold = true
                isUnder = true
            }
            val start = out.length
            out.append(inner)
            if (isBold) {
                out.setSpan(
                    StyleSpan(Typeface.BOLD),
                    start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            if (isUnder) {
                out.setSpan(
                    UnderlineSpan(),
                    start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            i = close + marker.length
        }
        return out
    }

    /** Bold/underline spans in the editor -> "**" / "__" markers for storage. */
    fun toMarkdown(editable: Editable): String {
        val boldRanges = editable.getSpans(0, editable.length, StyleSpan::class.java)
            .filter { it.style == Typeface.BOLD || it.style == Typeface.BOLD_ITALIC }
            .map { editable.getSpanStart(it) to editable.getSpanEnd(it) }
            .filter { (start, end) -> start >= 0 && end > start }
        val underRanges = editable.getSpans(0, editable.length, UnderlineSpan::class.java)
            .map { editable.getSpanStart(it) to editable.getSpanEnd(it) }
            .filter { (start, end) -> start >= 0 && end > start }
        data class Seg(val start: Int, val end: Int, val bold: Boolean, val under: Boolean)
        val segs = mutableListOf<Seg>()
        boldRanges.forEach { (start, end) ->
            val under = underRanges.any { it.first == start && it.second == end }
            segs += Seg(start, end, true, under)
        }
        underRanges.forEach { (start, end) ->
            if (segs.none { it.start == start && it.end == end }) {
                segs += Seg(start, end, false, true)
            }
        }
        if (segs.isEmpty()) return editable.toString()
        val out = StringBuilder()
        var pos = 0
        segs.sortedBy { it.start }.forEach { (start, end, bold, under) ->
            if (start < pos) return@forEach // overlapping span: skip
            out.append(editable.subSequence(pos, start))
            val inner = editable.subSequence(start, end)
            when {
                bold && under -> out.append("**__").append(inner).append("__**")
                bold -> out.append("**").append(inner).append("**")
                else -> out.append("__").append(inner).append("__")
            }
            pos = end
        }
        out.append(editable.subSequence(pos, editable.length))
        return out.toString()
    }
}
