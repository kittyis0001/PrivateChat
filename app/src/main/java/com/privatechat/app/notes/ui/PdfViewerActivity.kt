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
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.privatechat.app.R
import com.privatechat.app.notes.util.PdfTextExtractor
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

    private fun extractToNote() {
        val uri = pdfUri ?: return
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                PdfTextExtractor.extractText(this@PdfViewerActivity, uri)
            }
            if (text.isBlank()) {
                Toast.makeText(
                    this@PdfViewerActivity,
                    R.string.notes_pdf_no_text, Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            val intent = Intent(this@PdfViewerActivity, NoteEditorActivity::class.java)
                .putExtra(
                    NoteEditorActivity.EXTRA_TEMPLATE_TITLE,
                    uri.lastPathSegment ?: "PDF"
                )
                .putExtra(NoteEditorActivity.EXTRA_TEMPLATE_CONTENT, text)
            startActivity(intent)
            finish()
        }
    }

    override fun onDestroy() {
        renderer?.close()
        runCatching { pfd?.close() }
        currentBitmap?.recycle()
        super.onDestroy()
    }
}
