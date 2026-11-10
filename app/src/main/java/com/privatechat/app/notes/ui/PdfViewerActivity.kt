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
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
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
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

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
 * Password-protected PDFs: Android's PdfRenderer cannot open an
 * encrypted file at all, so a locked file asks for its password and
 * is decrypted and rendered with PDFBox instead (see
 * [promptPassword]). The same engine then serves page images and
 * text extraction for that file.
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
    private var cachedPdf: File? = null
    private var pdfBoxDoc: PDDocument? = null
    private var pdfBoxRenderer: PDFRenderer? = null
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
        if (!openPdf(uri)) {
            // Not readable directly — check on a background thread
            // whether it is a locked file before giving up.
            lifecycleScope.launch(Dispatchers.IO) {
                val encrypted = isEncryptedPdf(uri)
                withContext(Dispatchers.Main) {
                    if (encrypted) promptPassword(uri) else {
                        Toast.makeText(
                            this@PdfViewerActivity,
                            R.string.notes_pdf_open_failed, Toast.LENGTH_SHORT
                        ).show()
                        finish()
                    }
                }
            }
            return
        }
        findViewById<TextView>(R.id.pdfTitle).text =
            uri.lastPathSegment ?: "PDF"
        showPage(0)
    }

    /**
     * Definitive check for a password lock: PDFBox loads the file
     * with no password and throws InvalidPasswordException only
     * when it is encrypted. Runs on IO — it reads the file.
     */
    private fun isEncryptedPdf(uri: Uri): Boolean {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                PDDocument.load(input).close()
            }
            false
        } catch (e: InvalidPasswordException) {
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Asks for the PDF password and opens the file with PDFBox. */
    private fun promptPassword(uri: Uri) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.notes_pdf_password_hint)
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.notes_pdf_password_title)
            .setMessage(R.string.notes_pdf_password_message)
            .setView(input)
            .setPositiveButton(R.string.notes_pdf_open) { _, _ ->
                tryOpenWithPassword(uri, input.text.toString())
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun tryOpenWithPassword(uri: Uri, password: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val doc = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    PDDocument.load(input, password)
                } ?: throw IOException("no stream")
            }
            withContext(Dispatchers.Main) {
                doc.onSuccess {
                    pdfBoxDoc = it
                    pdfBoxRenderer = PDFRenderer(it)
                    pageCount = it.numberOfPages
                    findViewById<TextView>(R.id.pdfTitle).text =
                        uri.lastPathSegment ?: "PDF"
                    showPage(0)
                }.onFailure { e ->
                    if (e is InvalidPasswordException) {
                        Toast.makeText(
                            this@PdfViewerActivity,
                            R.string.notes_pdf_wrong_password, Toast.LENGTH_SHORT
                        ).show()
                        promptPassword(uri)
                    } else {
                        Toast.makeText(
                            this@PdfViewerActivity,
                            R.string.notes_pdf_open_failed, Toast.LENGTH_SHORT
                        ).show()
                        finish()
                    }
                }
            }
        }
    }

    /**
     * Opens the PDF for rendering. Direct open first (in-app files);
     * if the provider won't hand over a file descriptor — some chat
     * apps only serve a stream — copy the file into our own cache
     * and open the copy instead. False when the file cannot be read
     * at all, e.g. it is still downloading in the other app.
     */
    private fun openPdf(uri: Uri): Boolean {
        runCatching {
            pfd = contentResolver.openFileDescriptor(uri, "r")
            renderer = PdfRenderer(pfd!!)
            pageCount = renderer!!.pageCount
            return true
        }
        return runCatching {
            val cached = File(cacheDir, "opened-" + System.currentTimeMillis() + ".pdf")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(cached).use { output -> input.copyTo(output) }
            } ?: return false
            cachedPdf = cached
            pfd = ParcelFileDescriptor.open(cached, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd!!)
            pageCount = renderer!!.pageCount
            true
        }.getOrDefault(false)
    }

    private fun showPage(index: Int) {
        if (index !in 0 until pageCount) return
        currentPage = index
        findViewById<TextView>(R.id.pdfPageLabel).text =
            getString(R.string.notes_pdf_page_of, index + 1, pageCount)
        val box = pdfBoxRenderer
        if (box != null) {
            // PDFBox rendering is slower than PdfRenderer, so it
            // runs off the main thread and the page appears when
            // it is ready. The page check stops a slow render from
            // overwriting a newer page the user already moved to.
            val pageToShow = index
            lifecycleScope.launch(Dispatchers.Default) {
                val bitmap = runCatching {
                    box.renderImageWithDPI(pageToShow, 150f)
                }.getOrNull()
                withContext(Dispatchers.Main) {
                    if (bitmap != null && pageToShow == currentPage) {
                        currentBitmap?.recycle()
                        currentBitmap = bitmap
                        findViewById<ImageView>(R.id.pdfPageImage).setImageBitmap(bitmap)
                    } else {
                        bitmap?.recycle()
                    }
                }
            }
            return
        }
        val r = renderer ?: return
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
    }

    /** Renders one page on either engine — PdfRenderer, or PDFBox for a locked file. */
    private fun renderPageBitmap(index: Int): Bitmap? {
        val box = pdfBoxRenderer
        if (box != null) {
            return runCatching { box.renderImageWithDPI(index, 150f) }.getOrNull()
        }
        val r = renderer ?: return null
        return r.openPage(index).use { page ->
            val bitmap = Bitmap.createBitmap(
                page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888
            )
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
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
                val boxDoc = pdfBoxDoc
                if (boxDoc != null) {
                    // Locked file, already decrypted: PDFBox reads
                    // its embedded text directly.
                    runCatching { PDFTextStripper().getText(boxDoc) }.getOrDefault("")
                } else {
                    PdfTextExtractor.extractText(this@PdfViewerActivity, uri)
                }
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
        return try {
            val parts = mutableListOf<TextScanner.ScannedText>()
            for (index in 0 until pageCount) {
                val bitmap = renderPageBitmap(index) ?: continue
                try {
                    TextScanner.recognizeBitmap(bitmap).onSuccess { parts.add(it) }
                } finally {
                    bitmap.recycle()
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
        val baseName = pdfUri?.lastPathSegment
            ?.substringBeforeLast(".")
            ?.takeIf { it.isNotBlank() }
            ?.replace(Regex("[\\\\/:*?\"<>|]"), " ")
            ?.take(40) ?: "PDF"
        val resolver = contentResolver
        var saved = 0
        for (index in 0 until pageCount) {
            val bitmap = renderPageBitmap(index) ?: continue
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
        saved
    }

    override fun onDestroy() {
        renderer?.close()
        runCatching { pfd?.close() }
        runCatching { cachedPdf?.delete() }
        runCatching { pdfBoxDoc?.close() }
        currentBitmap?.recycle()
        super.onDestroy()
    }
}
