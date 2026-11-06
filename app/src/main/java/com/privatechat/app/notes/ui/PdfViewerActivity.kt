package com.privatechat.app.notes.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.privatechat.app.R
import com.privatechat.app.notes.util.GeminiSummarizer
import com.privatechat.app.notes.util.PdfTextExtractor
import com.privatechat.app.notes.util.TextScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Opens a PDF (e.g. a scanned document) page by page, and can pull
 * its readable text into a new note. Extraction is best-effort:
 * image-only PDFs have no text to extract, and the user is told so.
 */
class PdfViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PDF_URI = "pdf_uri"
    }

    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var currentBitmap: Bitmap? = null
    private var currentPage = 0
    private var pageCount = 0
    private var pdfUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_viewer)
        val uriString = intent.getStringExtra(EXTRA_PDF_URI)
        pdfUri = uriString?.let { Uri.parse(it) }
        findViewById<View>(R.id.pdfBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.pdfPrev).setOnClickListener { showPage(currentPage - 1) }
        findViewById<Button>(R.id.pdfNext).setOnClickListener { showPage(currentPage + 1) }
        findViewById<Button>(R.id.pdfExtractText).setOnClickListener { extractToNote() }
        val uri = pdfUri
        if (uri == null) {
            Toast.makeText(this, R.string.notes_pdf_open_failed, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        runCatching {
            pfd = contentResolver.openFileDescriptor(uri, "r")
            renderer = PdfRenderer(pfd!!)
            pageCount = renderer!!.pageCount
        }.onFailure {
            Toast.makeText(this, R.string.notes_pdf_open_failed, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        findViewById<TextView>(R.id.pdfTitle).text =
            uri.lastPathSegment ?: "PDF"
        showPage(0)
    }

    private fun showPage(index: Int) {
        val r = renderer ?: return
        if (index !in 0 until pageCount) return
        currentPage = index
        r.openPage(index).use { page ->
            val bitmap = Bitmap.createBitmap(
                page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888
            )
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            currentBitmap?.recycle()
            currentBitmap = bitmap
            findViewById<ImageView>(R.id.pdfPageImage).setImageBitmap(bitmap)
        }
        findViewById<TextView>(R.id.pdfPageLabel).text =
            getString(R.string.notes_pdf_page_of, index + 1, pageCount)
    }

    /**
     * Pulls the PDF's text into a new note. Embedded text first
     * (exact, when the PDF has any); for a scanned, image-only PDF
     * the extractor now honestly returns "" instead of binary
     * gibberish, so this falls back to OCR: each page is rendered
     * and read, and the recognised text becomes the note. The title
     * is an AI/local auto title from the text — never the raw file
     * name (that is how "911018545234675.pdf" became a note title).
     */
    private fun extractToNote() {
        val uri = pdfUri ?: return
        val progress = AlertDialog.Builder(this)
            .setMessage(getString(R.string.notes_scan_reading))
            .setCancelable(false)
            .create()
        progress.show()
        lifecycleScope.launch {
            var text = withContext(Dispatchers.IO) {
                PdfTextExtractor.extractText(this@PdfViewerActivity, uri)
            }
            var markdown: String? = null
            if (text.isBlank()) {
                // Image-only PDF: OCR the rendered pages instead.
                // The progress dialog is modal, so no page button can
                // touch the renderer while this runs on IO.
                val ocr = withContext(Dispatchers.IO) { ocrPdfPages() }
                ocr.onSuccess {
                    text = it.plainText
                    markdown = it.markdownText
                }
            }
            progress.dismiss()
            if (text.isBlank()) {
                Toast.makeText(
                    this@PdfViewerActivity,
                    R.string.notes_pdf_no_text, Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            val fallbackTitle = uri.lastPathSegment
                ?.substringBeforeLast(".")
                ?.takeIf { it.isNotBlank() } ?: "PDF"
            val title = GeminiSummarizer.smartTitle(this@PdfViewerActivity, text)
                .ifBlank { fallbackTitle }
            val intent = Intent(this@PdfViewerActivity, NoteEditorActivity::class.java)
                .putExtra(NoteEditorActivity.EXTRA_TEMPLATE_TITLE, title)
                .putExtra(NoteEditorActivity.EXTRA_TEMPLATE_CONTENT, markdown ?: text)
            startActivity(intent)
            finish()
        }
    }

    /** Renders every page and OCRs it; failure when nothing is readable. */
    private suspend fun ocrPdfPages(): Result<TextScanner.ScannedText> {
        val r = renderer ?: return Result.failure(IllegalStateException("no renderer"))
        return try {
            val parts = mutableListOf<TextScanner.ScannedText>()
            for (index in 0 until pageCount) {
                r.openPage(index).use { page ->
                    val bitmap = Bitmap.createBitmap(
                        page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888
                    )
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    try {
                        TextScanner.recognizeBitmap(bitmap).onSuccess { parts.add(it) }
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
            val plain = parts.joinToString("\n\n") { it.plainText }.trim()
            val markdown = parts.joinToString("\n\n") { it.markdownText }.trim()
            if (plain.isBlank()) Result.failure(IllegalStateException("NO_TEXT"))
            else Result.success(TextScanner.ScannedText(plain, markdown))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun onDestroy() {
        renderer?.close()
        runCatching { pfd?.close() }
        currentBitmap?.recycle()
        super.onDestroy()
    }
}
