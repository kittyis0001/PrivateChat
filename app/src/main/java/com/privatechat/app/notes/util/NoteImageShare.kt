package com.privatechat.app.notes.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.util.DisplayMetrics
import android.util.TypedValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** Renders a note as a pretty 1080x1350 image (the same image that
 * "Share as image" sends and "Save as image" stores in the Gallery).
 * The shared PNG lives in cacheDir/shared_notes via FileProvider. */
object NoteImageShare {

    /**
     * Draws the note image: pink header, body text, Kitty Notes footer.
     * The body text AUTO-FITS: it starts at 26sp and shrinks until the
     * whole note fits the page, so a long note is never cut off. If
     * even the 16sp floor is not enough, the canvas grows taller
     * (still ONE image) instead of clipping — capped at 6000px so a
     * giant note cannot exhaust memory.
     */
    fun renderNoteImage(context: Context, title: String, body: String, colorHex: String): Bitmap {
        val w = 1080
        val dm = context.resources.displayMetrics
        val safeBody = body.ifBlank { " " }
        val bodySpanned = RichText.toSpanned(safeBody)
        val availW = w - 128
        val availH = 1350 - 320
        var bodySp = 26f
        val minSp = 16f
        var layout = buildBodyLayout(bodySpanned, bodySp, availW, dm)
        while (layout.height > availH && bodySp > minSp) {
            bodySp = maxOf(minSp, bodySp * 0.9f)
            layout = buildBodyLayout(bodySpanned, bodySp, availW, dm)
        }
        val h = if (layout.height > availH) {
            minOf(6000, 320 + layout.height + 70)
        } else {
            1350
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bg = runCatching { Color.parseColor(colorHex) }.getOrNull()
            ?: Color.parseColor("#FFF3B0")
        canvas.drawColor(bg)
        // Pink header band.
        val headerPaint = Paint().apply { color = Color.parseColor("#FF8FB1") }
        canvas.drawRect(0f, 0f, w.toFloat(), 180f, headerPaint)
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 30f, dm)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(title.ifBlank { "Kitty Notes" }.take(30), 64f, 110f, titlePaint)
        // Body text (auto-fitted above).
        canvas.save()
        canvas.translate(64f, 250f)
        canvas.clipRect(0f, 0f, (w - 128).toFloat(), (h - 320).toFloat())
        layout.draw(canvas)
        canvas.restore()
        // Footer.
        val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E75480")
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 20f, dm)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("Kitty Notes", 64f, (h - 64).toFloat(), footerPaint)
        return bitmap
    }

    private fun buildBodyLayout(
        text: Spanned,
        sp: Float,
        width: Int,
        dm: DisplayMetrics
    ): StaticLayout {
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF333333.toInt()
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, dm)
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, bodyPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.3f)
            .setIncludePad(false)
            .build()
    }

    suspend fun shareNoteAsImage(
        context: Context,
        title: String,
        body: String,
        colorHex: String
    ): Uri = withContext(Dispatchers.IO) {
        val bitmap = renderNoteImage(context, title, body, colorHex)
        val dir = File(context.cacheDir, "shared_notes").apply { mkdirs() }
        val file = File(dir, "note-" + System.currentTimeMillis() + ".png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    }

    /** Saves the same rendered image to Pictures/Kitty Notes so it shows in the Gallery. */
    suspend fun saveNoteImageToGallery(
        context: Context,
        title: String,
        body: String,
        colorHex: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                throw UnsupportedOperationException("API 29+ required")
            }
            val bitmap = renderNoteImage(context, title, body, colorHex)
            val fileName = title.ifBlank { "Kitty Note" }.trim().take(40)
                .replace(Regex("[\\\\/:*?\"<>|]"), " ") + ".png"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/Kitty Notes"
                )
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: throw IllegalStateException("Could not open Gallery")
            fileName
        }
    }
}
