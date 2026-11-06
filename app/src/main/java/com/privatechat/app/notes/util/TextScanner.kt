package com.privatechat.app.notes.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reads the printed text out of a photo or a scanned document page
 * (ML Kit on-device text recognition, Latin script — no model is
 * bundled in the APK, Play services downloads it on first use).
 *
 * This is the fix for "Scan document" saving gibberish: the old flow
 * only produced a PDF and then tried to pull text out of the PDF's
 * raw bytes. A scanned PDF is pictures, not text, so the extractor
 * returned binary junk. OCR reads the picture itself instead.
 *
 * What is preserved, exactly:
 * - Case: the recognised text is used verbatim, so a word that is
 *   CAPITAL in the photo stays CAPITAL in the note.
 * - Line breaks: lines stay lines, blocks (paragraphs) are kept
 *   apart with a blank line, so the note looks like the page.
 *
 * What is best-effort, and why it is labelled so here: ML Kit's
 * text API reports the words and their boxes, but NOT the font
 * weight — there is no "is this word bold" field to read. So bold
 * is detected by size: a line whose height is clearly bigger than
 * the page's typical line (a title / section heading, which in
 * printed documents is almost always bold too) is marked bold.
 * An inline bold word inside a normal-size line cannot be told
 * apart from its neighbours by any on-device OCR signal, so it is
 * kept as normal text rather than guessed wrong. Bold lines are
 * stored as **markers** in the plain-text note (see [RichText]);
 * the editor renders them bold and PDF / image export does too.
 *
 * Bengali script is a known gap: ML Kit's on-device recogniser
 * supports Latin (plus Chinese / Devanagari / Japanese / Korean
 * with their own models), but there is no on-device Bengali model,
 * so Bengali pages may recognise poorly or not at all.
 */
object TextScanner {

    /** Plain text as recognised, plus the same text with **bold** markers. */
    data class ScannedText(val plainText: String, val markdownText: String)

    suspend fun recognizeUri(context: Context, uri: Uri): Result<ScannedText> {
        return try {
            Result.success(recognize(InputImage.fromFilePath(context, uri)))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun recognizeBitmap(bitmap: Bitmap): Result<ScannedText> {
        return try {
            Result.success(recognize(InputImage.fromBitmap(bitmap, 0)))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** OCRs every page and joins them (blank line between pages). */
    suspend fun recognizeUris(context: Context, uris: List<Uri>): Result<ScannedText> {
        return try {
            val parts = uris.map { uri -> recognizeUri(context, uri).getOrThrow() }
            val plain = parts.joinToString("\n\n") { it.plainText }.trim()
            val markdown = parts.joinToString("\n\n") { it.markdownText }.trim()
            if (plain.isBlank()) throw IllegalStateException("NO_TEXT")
            Result.success(ScannedText(plain, markdown))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun recognize(image: InputImage): ScannedText {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val result = suspendCancellableCoroutine<Text> { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
                cont.invokeOnCancellation { recognizer.close() }
            }
            return buildScannedText(result)
        } finally {
            recognizer.close()
        }
    }

    private fun buildScannedText(result: Text): ScannedText {
        val lines = result.textBlocks.flatMap { it.lines }
        // Typical line height on this page (median, so one big title
        // cannot move it). A line 25% taller than this is a heading.
        val heights = lines.mapNotNull { it.boundingBox?.height() }
            .filter { it > 0 }.sorted()
        val baseline = when {
            heights.isEmpty() -> 0
            heights.size % 2 == 1 -> heights[heights.size / 2]
            else -> (heights[heights.size / 2 - 1] + heights[heights.size / 2]) / 2
        }
        val plain = StringBuilder()
        val markdown = StringBuilder()
        result.textBlocks.forEachIndexed { blockIndex, block ->
            if (blockIndex > 0) {
                plain.append("\n\n")
                markdown.append("\n\n")
            }
            var firstLine = true
            block.lines.forEach { line ->
                val lineText = line.elements.joinToString(" ") { it.text }.trim()
                if (lineText.isEmpty()) return@forEach
                if (!firstLine) {
                    plain.append('\n')
                    markdown.append('\n')
                }
                firstLine = false
                val bold = baseline > 0 &&
                    (line.boundingBox?.height() ?: 0) >= baseline * 1.25f
                plain.append(lineText)
                if (bold) markdown.append("**").append(lineText).append("**")
                else markdown.append(lineText)
            }
        }
        val plainText = plain.toString().trim()
        if (plainText.isBlank()) throw IllegalStateException("NO_TEXT")
        return ScannedText(plainText, markdown.toString().trim())
    }
}
