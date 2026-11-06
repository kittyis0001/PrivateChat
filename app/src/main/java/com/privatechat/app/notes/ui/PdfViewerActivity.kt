package com.privatechat.app.notes.ui

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
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

    // Edit mode: freehand drawing + stamped text on top of a page.
    // Marks are kept per page (in the page bitmap's coordinates) so
    // switching pages never loses them, and saving burns them into
    // a NEW pdf — the original file is never overwritten.
    private var editMode = false
    private val pageAnnotations =
        mutableMapOf<Int, MutableList<PdfAnnotateView.Element>>()
    private lateinit var annotateView: PdfAnnotateView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_viewer)
        val uriString = intent.getStringExtra(EXTRA_PDF_URI)
        pdfUri = uriString?.let { Uri.parse(it) }
        findViewById<View>(R.id.pdfBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.pdfPrev).setOnClickListener { showPage(currentPage - 1) }
        findViewById<Button>(R.id.pdfNext).setOnClickListener { showPage(currentPage + 1) }
        findViewById<Button>(R.id.pdfExtractText).setOnClickListener { extractToNote() }
        annotateView = findViewById(R.id.pdfAnnotateView)
        annotateView.onTextTap = { x, y -> promptAddText(x, y) }
        findViewById<ImageButton>(R.id.pdfEdit).setOnClickListener { enterEditMode() }
        findViewById<Button>(R.id.pdfEditDraw).setOnClickListener {
            setAnnotateMode(PdfAnnotateView.Mode.DRAW)
        }
        findViewById<Button>(R.id.pdfEditText).setOnClickListener {
            setAnnotateMode(PdfAnnotateView.Mode.TEXT)
        }
        findViewById<Button>(R.id.pdfEditUndo).setOnClickListener { annotateView.undo() }
        findViewById<Button>(R.id.pdfEditClear).setOnClickListener { confirmClearPage() }
        findViewById<Button>(R.id.pdfEditSave).setOnClickListener { saveEditedPdf() }
        findViewById<Button>(R.id.pdfEditCancel).setOnClickListener { exitEditMode() }
        findViewById<Button>(R.id.pdfColorBlack).setOnClickListener { setPenColor(Color.BLACK) }
        findViewById<Button>(R.id.pdfColorRed).setOnClickListener { setPenColor(Color.RED) }
        findViewById<Button>(R.id.pdfColorBlue).setOnClickListener { setPenColor(Color.BLUE) }
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
        if (editMode) stashCurrentPageAnnotations()
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
            if (editMode) {
                annotateView.setPage(bitmap, pageAnnotations[index].orEmpty())
            }
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

    // ---------------- PDF edit mode (draw + add text, save a copy) ----------------

    private fun enterEditMode() {
        val bmp = currentBitmap ?: return
        editMode = true
        annotateView.setPage(bmp, pageAnnotations[currentPage].orEmpty())
        findViewById<ImageView>(R.id.pdfPageImage).visibility = View.GONE
        annotateView.visibility = View.VISIBLE
        findViewById<View>(R.id.pdfEditBar).visibility = View.VISIBLE
        findViewById<View>(R.id.pdfEdit).visibility = View.GONE
        setAnnotateMode(PdfAnnotateView.Mode.DRAW)
        setPenColor(Color.BLACK)
    }

    private fun exitEditMode() {
        stashCurrentPageAnnotations()
        editMode = false
        annotateView.visibility = View.GONE
        findViewById<ImageView>(R.id.pdfPageImage).visibility = View.VISIBLE
        findViewById<View>(R.id.pdfEditBar).visibility = View.GONE
        findViewById<View>(R.id.pdfEdit).visibility = View.VISIBLE
    }

    private fun stashCurrentPageAnnotations() {
        if (!editMode) return
        val elems = annotateView.getElements()
        if (elems.isEmpty()) pageAnnotations.remove(currentPage)
        else pageAnnotations[currentPage] = elems.toMutableList()
    }

    private fun setAnnotateMode(mode: PdfAnnotateView.Mode) {
        annotateView.mode = mode
        findViewById<Button>(R.id.pdfEditDraw).text =
            if (mode == PdfAnnotateView.Mode.DRAW) "● ${getString(R.string.notes_pdf_edit_draw)}"
            else getString(R.string.notes_pdf_edit_draw)
        findViewById<Button>(R.id.pdfEditText).text =
            if (mode == PdfAnnotateView.Mode.TEXT) "● ${getString(R.string.notes_pdf_edit_text)}"
            else getString(R.string.notes_pdf_edit_text)
    }

    private fun setPenColor(color: Int) {
        annotateView.penColor = color
        annotateView.textColor = color
        findViewById<Button>(R.id.pdfColorBlack).alpha =
            if (color == Color.BLACK) 1f else 0.4f
        findViewById<Button>(R.id.pdfColorRed).alpha =
            if (color == Color.RED) 1f else 0.4f
        findViewById<Button>(R.id.pdfColorBlue).alpha =
            if (color == Color.BLUE) 1f else 0.4f
    }

    private fun promptAddText(x: Float, y: Float) {
        val input = EditText(this).apply {
            hint = getString(R.string.notes_pdf_add_text_hint)
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.notes_pdf_add_text_title))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                annotateView.addText(x, y, input.text.toString().trim())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmClearPage() {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.notes_pdf_edit_clear_confirm))
            .setPositiveButton(android.R.string.ok) { _, _ -> annotateView.clearAll() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Saves a NEW pdf (original untouched) into Downloads/Kitty Notes:
     * every page rendered on white, with this session's drawings and
     * stamped text burned in. The saved pages are images, so their
     * text cannot be selected like the original's — that is the price
     * of editing without a heavy PDF engine, and the user is told so.
     */
    private fun saveEditedPdf() {
        stashCurrentPageAnnotations()
        if (pageAnnotations.isEmpty()) {
            Toast.makeText(this, R.string.notes_pdf_edit_nothing, Toast.LENGTH_SHORT).show()
            return
        }
        val progress = AlertDialog.Builder(this)
            .setMessage(getString(R.string.notes_pdf_edit_saving))
            .setCancelable(false)
            .create()
        progress.show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { buildEditedPdf() }
            progress.dismiss()
            result.onSuccess { (name, uri) ->
                exitEditMode()
                AlertDialog.Builder(this@PdfViewerActivity)
                    .setTitle(getString(R.string.notes_pdf_saved_title))
                    .setMessage(getString(R.string.notes_pdf_saved, name))
                    .setPositiveButton(getString(R.string.notes_pdf_open)) { _, _ ->
                        val intent = Intent(this@PdfViewerActivity, PdfViewerActivity::class.java)
                            .putExtra(EXTRA_PDF_URI, uri.toString())
                        startActivity(intent)
                    }
                    .setNegativeButton(android.R.string.ok, null)
                    .show()
            }.onFailure {
                Toast.makeText(
                    this@PdfViewerActivity,
                    R.string.notes_export_failed, Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun buildEditedPdf(): Result<Pair<String, Uri>> = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw UnsupportedOperationException("API 29+ required")
        }
        val r = renderer ?: throw IllegalStateException("no renderer")
        val baseName = pdfUri?.lastPathSegment
            ?.substringBeforeLast(".")
            ?.takeIf { it.isNotBlank() }
            ?.replace(Regex("[\\\\/:*?\"<>|]"), " ")
            ?.take(40) ?: "PDF"
        val fileName = "$baseName - edited.pdf"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/Kitty Notes"
            )
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert failed")
        resolver.openOutputStream(uri)?.use { out ->
            val doc = PdfDocument()
            try {
                for (index in 0 until pageCount) {
                    r.openPage(index).use { page ->
                        val bitmap = Bitmap.createBitmap(
                            page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888
                        )
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val annotated =
                            pageAnnotations[index]?.takeIf { it.isNotEmpty() }?.let { elems ->
                                val copy = Bitmap.createBitmap(
                                    bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888
                                )
                                Canvas(copy).apply {
                                    drawBitmap(bitmap, 0f, 0f, null)
                                    PdfAnnotateView.drawElementsOnto(this, elems)
                                }
                                bitmap.recycle()
                                copy
                            } ?: bitmap
                        val pageInfo =
                            PdfDocument.PageInfo.Builder(page.width, page.height, index + 1).create()
                        val docPage = doc.startPage(pageInfo)
                        docPage.canvas.drawBitmap(
                            annotated, null,
                            RectF(0f, 0f, page.width.toFloat(), page.height.toFloat()), null
                        )
                        doc.finishPage(docPage)
                        annotated.recycle()
                    }
                }
                doc.writeTo(out)
            } finally {
                doc.close()
            }
        } ?: throw IllegalStateException("Could not open Downloads")
        fileName to uri
    }

    override fun onDestroy() {
        renderer?.close()
        runCatching { pfd?.close() }
        currentBitmap?.recycle()
        super.onDestroy()
    }
}
