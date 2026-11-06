package com.privatechat.app.notes.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** Renders a note as a pretty 1080x1350 image for sharing. The PNG
 * lives in cacheDir/shared_notes and is shared via FileProvider. */
object NoteImageShare {

    suspend fun shareNoteAsImage(
        context: Context,
        title: String,
        body: String,
        colorHex: String
    ): Uri = withContext(Dispatchers.IO) {
        val w = 1080
        val h = 1350
        val dm = context.resources.displayMetrics
        val safeBody = body.ifBlank { " " }
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
        // Body text.
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF333333.toInt()
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 26f, dm)
        }
        val layout = StaticLayout.Builder.obtain(safeBody, 0, safeBody.length, bodyPaint, w - 128)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.3f)
            .setIncludePad(false)
            .build()
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
        val dir = File(context.cacheDir, "shared_notes").apply { mkdirs() }
        val file = File(dir, "note-" + System.currentTimeMillis() + ".png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    }
}
