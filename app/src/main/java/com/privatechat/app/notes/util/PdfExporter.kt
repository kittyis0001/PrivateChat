package com.privatechat.app.notes.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
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
import android.util.TypedValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * Renders a note as a PDF with Android's StaticLayout, so Bengali
 * text keeps its correct shaping (the old manual breakText split
 * could break grapheme clusters and mangle Bengali). Two ways out:
 * straight into Downloads via MediaStore, or into a user-picked
 * SAF Uri (the old export flow).
 */
object PdfExporter {
    private const val PAGE_W = 595 // A4 in points
    private const val PAGE_H = 842
    private const val MARGIN = 40

    private fun buildLayout(context: Context, title: String, text: String): StaticLayout {
        val dm = context.resources.displayMetrics
        val bodyPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, dm)
        val titlePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 19f, dm)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF222222.toInt()
            textSize = bodyPx
        }
        val full = SpannableStringBuilder()
        if (title.isNotBlank()) {
            val start = full.length
            full.append(title.trim())
            full.setSpan(
                AbsoluteSizeSpan(titlePx.toInt(), false),
                start, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            full.setSpan(
                StyleSpan(Typeface.BOLD),
                start, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            full.append("\n\n")
        }
        full.append(text.ifBlank { " " })
        return StaticLayout.Builder.obtain(full, 0, full.length, paint, PAGE_W - 2 * MARGIN)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.25f)
            .setIncludePad(true)
            .build()
    }

    private fun render(context: Context, title: String, text: String): PdfDocument {
        val layout = buildLayout(context, title, text)
        val doc = PdfDocument()
        val contentHeight = PAGE_H - 2 * MARGIN
        var pageStart = 0
        var pageNo = 1
        // do-while: even an empty note gets exactly one page.
        do {
            val pageInfo = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create()
            val page = doc.startPage(pageInfo)
            val canvas: Canvas = page.canvas
            canvas.save()
            canvas.clipRect(
                MARGIN.toFloat(), MARGIN.toFloat(),
                (PAGE_W - MARGIN).toFloat(), (PAGE_H - MARGIN).toFloat()
            )
            canvas.translate(MARGIN.toFloat(), (MARGIN - pageStart).toFloat())
            layout.draw(canvas)
            canvas.restore()
            doc.finishPage(page)
            pageStart += contentHeight
            pageNo++
        } while (pageStart < layout.height)
        return doc
    }

    private fun renderAndWrite(context: Context, title: String, text: String, out: OutputStream) {
        val doc = render(context, title, text)
        try {
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    /** Saves the PDF straight into Downloads/Kitty Notes (API 29+). */
    suspend fun saveToDownloads(context: Context, title: String, text: String): Result<String> =
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
                    renderAndWrite(context, title, text, out)
                } ?: throw IllegalStateException("Could not open Downloads")
                fileName
            }
        }

    /** Writes the PDF to a user-picked SAF Uri (old export flow). */
    suspend fun writeToUri(context: Context, uri: Uri, title: String, text: String) =
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                renderAndWrite(context, title, text, out)
            } ?: throw IllegalStateException("Could not open file")
        }
}
