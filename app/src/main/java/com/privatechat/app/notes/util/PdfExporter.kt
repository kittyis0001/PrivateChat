package com.privatechat.app.notes.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.StyleSpan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders a note as a professional PDF (A4, like a print preview):
 * a "Made with Kitty Notes" header with the date on the first page,
 * title + body, and a "1 / 2" page footer on every page.
 *
 * Two real bugs fixed here: text sizes are in POINTS (the PDF unit),
 * never display pixels — the old code sized text with the phone's
 * density, so the PDF looked different on every phone and paginated
 * wrong (px heights compared against pt page heights); and the title
 * was printed twice, because callers passed title+body as the text
 * while the layout prepended the title again (callers now pass the
 * body only). Bengali keeps its correct shaping via StaticLayout.
 *
 * Two ways out: straight into Downloads via MediaStore (returns the
 * file name + Uri so the caller can offer to open it), or into a
 * user-picked SAF Uri (the old export flow).
 */
object PdfExporter {
    private const val PAGE_W = 595 // A4 in points
    private const val PAGE_H = 842
    private const val MARGIN = 40
    private const val HEADER_H = 96 // first-page header (glyph + title + date)

    private fun buildLayout(title: String, text: String): StaticLayout {
        // Points (1/72 inch), the PDF page unit — never display pixels.
        val bodyPt = 12f
        val titlePt = 19f
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF222222.toInt()
            textSize = bodyPt
        }
        val full = SpannableStringBuilder()
        if (title.isNotBlank()) {
            val start = full.length
            full.append(title.trim())
            full.setSpan(
                AbsoluteSizeSpan(titlePt.toInt(), false),
                start, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            full.setSpan(
                StyleSpan(Typeface.BOLD),
                start, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            full.append("\n\n")
        }
        full.append(RichText.toSpanned(text.ifBlank { " " }))
        return StaticLayout.Builder.obtain(full, 0, full.length, paint, PAGE_W - 2 * MARGIN)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.25f)
            .setIncludePad(true)
            .build()
    }

    /** Mini notepad glyph + "Made with Kitty Notes" + date, centered. */
    private fun drawHeader(canvas: Canvas, dateText: String) {
        val cx = PAGE_W / 2f
        val top = MARGIN.toFloat()
        val glyphW = 30f
        val glyphH = 34f
        val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF8FB1.toInt() }
        canvas.drawRoundRect(
            RectF(cx - glyphW / 2, top, cx + glyphW / 2, top + glyphH),
            6f, 6f, glyphPaint
        )
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 2f
        }
        for (i in 0..2) {
            val y = top + 12f + i * 7f
            canvas.drawLine(cx - 10f, y, cx + 10f, y, linePaint)
        }
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF222222.toInt()
            textSize = 11f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("Made with Kitty Notes", cx, top + glyphH + 18f, titlePaint)
        val datePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF888888.toInt()
            textSize = 9f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(dateText, cx, top + glyphH + 34f, datePaint)
    }

    /** "1 / 2" page number, centered in the bottom margin. */
    private fun drawFooter(canvas: Canvas, pageNo: Int, totalPages: Int) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF888888.toInt()
            textSize = 10f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("$pageNo / $totalPages", PAGE_W / 2f, (PAGE_H - 18).toFloat(), paint)
    }

    private fun render(title: String, text: String): PdfDocument {
        val layout = buildLayout(title, text)
        val doc = PdfDocument()
        val contentHeight = PAGE_H - 2 * MARGIN // 762 pt
        val dateText = SimpleDateFormat("dd MMM yyyy hh:mma", Locale.US).format(Date())
        // Page capacities in points: the first page loses the header.
        // do-while is gone: even an empty note gets exactly one page.
        val capacities = mutableListOf<Int>()
        var remaining = layout.height
        var first = true
        while (remaining > 0) {
            capacities.add(if (first) contentHeight - HEADER_H else contentHeight)
            remaining -= capacities.last()
            first = false
        }
        val totalPages = capacities.size
        var offset = 0
        for ((index, capacity) in capacities.withIndex()) {
            val pageNo = index + 1
            val pageInfo = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create()
            val page = doc.startPage(pageInfo)
            val canvas: Canvas = page.canvas
            val bodyTop = if (index == 0) MARGIN + HEADER_H else MARGIN
            if (index == 0) drawHeader(canvas, dateText)
            canvas.save()
            canvas.clipRect(
                MARGIN.toFloat(), bodyTop.toFloat(),
                (PAGE_W - MARGIN).toFloat(), (PAGE_H - MARGIN).toFloat()
            )
            canvas.translate(MARGIN.toFloat(), (bodyTop - offset).toFloat())
            layout.draw(canvas)
            canvas.restore()
            drawFooter(canvas, pageNo, totalPages)
            doc.finishPage(page)
            offset += capacity
        }
        return doc
    }

    private fun renderAndWrite(title: String, text: String, out: OutputStream) {
        val doc = render(title, text)
        try {
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    /** Saves the PDF straight into Downloads/Kitty Notes (API 29+). */
    suspend fun saveToDownloads(context: Context, title: String, text: String): Result<Pair<String, Uri>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    throw UnsupportedOperationException("API 29+ required")
                }
                val fileName = title.ifBlank { "Kitty Note" }.trim().take(40)
                    .replace(Regex("[\\\\/:*?\"<>|]"), " ") + ".pdf"
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/Kitty Notes"
                    )
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("MediaStore insert failed")
                resolver.openOutputStream(uri)?.use { out ->
                    renderAndWrite(title, text, out)
                } ?: throw IllegalStateException("Could not open Downloads")
                fileName to uri
            }
        }

    /** Writes the PDF to a user-picked SAF Uri (old export flow). */
    suspend fun writeToUri(context: Context, uri: Uri, title: String, text: String) =
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                renderAndWrite(title, text, out)
            } ?: throw IllegalStateException("Could not open file")
        }
}
