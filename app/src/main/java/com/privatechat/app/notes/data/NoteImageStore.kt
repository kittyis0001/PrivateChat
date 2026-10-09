package com.privatechat.app.notes.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Stores note photos as downscaled JPEGs in the app-private files
 * directory (files/kitty_note_images/). Local device storage only —
 * photos are never uploaded anywhere and never touch chat media.
 */
object NoteImageStore {

    private const val DIR_NAME = "kitty_note_images"
    private const val MAX_DIMENSION = 1600
    const val MAX_IMAGES_PER_NOTE = 10

    private fun dir(context: Context): File =
        File(context.applicationContext.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    fun file(context: Context, fileName: String): File = File(dir(context), fileName)

    fun newFileName(): String = "note_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.jpg"

    /**
     * Copies the picked image into note storage, downscaled so huge
     * camera photos cannot cause jank or run the app out of memory.
     * Returns the stored file name, or null if the image is unreadable.
     */
    suspend fun importImage(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val resolver = context.applicationContext.contentResolver
        // First pass: bounds only, so we can pick a safe sample size.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }.getOrNull()
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_DIMENSION || bounds.outHeight / (sample * 2) >= MAX_DIMENSION) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull() ?: return@withContext null

        val name = newFileName()
        val out = file(context, name)
        val ok = runCatching {
            FileOutputStream(out).use { fos -> bitmap.compress(Bitmap.CompressFormat.JPEG, 85, fos) }
        }.isSuccess
        bitmap.recycle()
        if (ok) name else { out.delete(); null }
    }
}
