package com.privatechat.app.notes.ui

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
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
 * Opens a PDF page by page, and can pull its readable text into a new
 * note or save every page as an image in the Gallery. Extraction is
 * best-effort: image-only PDFs have no text to extract, and the user
 * is told so.
 *
 * Opens two ways: from inside the app with [EXTRA_PDF_URI], or from
 * any other app through the system "Open with" chooser
 * (ACTION_VIEW + application/pdf, see the manifest) — the shared
 * file arrives as [Intent.getData].
 *
 * There is no edit mode in this viewer on purpose: drawing on pages
 * was removed (2026-11-10), so the viewer only reads.
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
        pdfUri = intent.getStringExtra(EXTRA_PDF_URI)?.let { Uri.parse(it) }
            ?: if (intent.action == Intent.ACTION_VIEW) intent.data else null
        findViewById<View>(R.id.pdfBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.pdfPrev).setOnClickListener { showPage(currentPage - 1) }
        findViewById<Button>(R.id.pdfNext).setOnClickListener { showPage(currentPage + 1) }
        findViewById<Button>(R.id.pdfExtractText).setOnClickListener { extractToNote() }
        findViewById<Button>(R.id.pdfSaveImages).setOnClickListener { savePagesAsImages() }
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
            // PdfRenderer paints only the page content, never the
            // paper: a fresh bitmap is transparent, and transparent
            // over the viewer's dark background looked like a black
            // page. Fill white first, like real paper.
            bitmap.eraseColor(Color.WHITE)
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
                    bitmap.eraseColor(Color.WHITE)
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

    /**
     * Saves every page as a JPEG in Pictures/Kitty Notes so the pages
     * show in the Gallery. Same MediaStore pattern as the note-image
     * and QR saves (API 29+). The progress dialog is modal, so no
     * page button can touch the renderer while this runs on IO.
     */
    private fun savePagesAsImages() {
        if (pageCount == 0) return
        val progress = AlertDialog.Builder(this)
            .setMessage(getString(R.string.notes_pdf_images_saving))
            .setCancelable(false)
            .create()
        progress.show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { buildPageImages() }
            progress.dismiss()
            result.onSuccess { count ->
                Toast.makeText(
                    this@PdfViewerActivity,
                    getString(R.string.notes_pdf_images_saved, count),
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure {
                Toast.makeText(
                    this@PdfViewerActivity,
                    R.string.notes_export_failed, Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun buildPageImages(): Result<Int> = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw UnsupportedOperationException("API 29+ required")
        }
        val r = renderer ?: throw IllegalStateException("no renderer")
        val baseName = pdfUri?.lastPathSegment
            ?.substringBeforeLast(".")
            ?.takeIf { it.isNotBlank() }
            ?.replace(Regex("[\\\\/:*?\"<>|]"), " ")
            ?.take(40) ?: "PDF"
        val resolver = contentResolver
        var saved = 0
        for (index in 0 until pageCount) {
            r.openPage(index).use { page ->
                val bitmap = Bitmap.createBitmap(
                    page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888
                )
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                try {
                    val fileName = "$baseName - p${index + 1}.jpg"
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            Environment.DIRECTORY_PICTURES + "/Kitty Notes"
                        )
                    }
                    val uri = resolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                    ) ?: throw IllegalStateException("MediaStore insert failed")
                    resolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    } ?: throw IllegalStateException("Could not open Gallery")
                    saved++
                } finally {
                    bitmap.recycle()
                }
            }
        }
        saved
    }

    override fun onDestroy() {
        renderer?.close()
        runCatching { pfd?.close() }
        currentBitmap?.recycle()
        super.onDestroy()
    }
}
