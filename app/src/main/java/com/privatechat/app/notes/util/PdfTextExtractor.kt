package com.privatechat.app.notes.util

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * Lightweight text extractor for simple unencrypted PDFs (the kind
 * Android's PdfDocument and most scanners produce). Reads stream
 * objects, inflates FlateDecode streams, and pulls text from
 * (...) literal strings, treating Td/T* as line breaks. Returns ""
 * when nothing readable is found — never throws.
 *
 * Two guards against the gibberish bug (a scanned, image-only PDF
 * used to save a note full of binary junk): only streams that look
 * like real text-drawing content (BT … Tj/TJ operators) are read —
 * image XObjects, font files and XMP metadata never contain those,
 * so they contribute nothing — and the result is rejected when its
 * words are implausibly long on average (binary soup has no spaces).
 * An image-only PDF therefore honestly returns "", and the caller
 * falls back to OCR instead of saving garbage.
 */
object PdfTextExtractor {

    fun extractText(context: Context, uri: Uri): String = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return ""
        if (bytes.size > 20 * 1024 * 1024) return ""
        val text = String(bytes, Charsets.ISO_8859_1)
        val out = StringBuilder()
        var searchFrom = 0
        while (true) {
            val s = text.indexOf("stream", searchFrom)
            if (s < 0) break
            var dataStart = s + "stream".length
            if (dataStart < text.length && text[dataStart] == '\r') dataStart++
            if (dataStart < text.length && text[dataStart] == '\n') dataStart++
            val e = text.indexOf("endstream", dataStart)
            if (e < 0) break
            val raw = text.substring(dataStart, e).toByteArray(Charsets.ISO_8859_1)
            val decoded = inflate(raw) ?: raw
            if (isTextStream(decoded)) extractFromStream(decoded, out)
            out.append('\n')
            searchFrom = e + "endstream".length
        }
        val cleaned = out.toString()
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
            .take(20000)
        if (cleaned.isEmpty()) return ""
        // Binary soup has almost no spaces: reject it rather than
        // saving it as a note. Real text averages ~5 letters a word.
        val words = cleaned.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val avgWord = if (words.isEmpty()) 0.0
            else words.sumOf { it.length }.toDouble() / words.size
        if (words.size >= 10 && avgWord > 12.0) return ""
        cleaned
    }.getOrElse { "" }

    /** True only for a content stream that draws text (not an image, font or metadata stream). */
    private fun isTextStream(decoded: ByteArray): Boolean {
        if (decoded.isEmpty() || decoded.contains(0)) return false
        val s = String(decoded, Charsets.ISO_8859_1)
        return s.contains("BT") && (s.contains("Tj") || s.contains("TJ"))
    }

    private fun inflate(data: ByteArray): ByteArray? = runCatching {
        val inflater = Inflater(true)
        inflater.setInput(data)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        inflater.end()
        out.toByteArray()
    }.getOrNull()

    private fun extractFromStream(decoded: ByteArray, out: StringBuilder) {
        val s = String(decoded, Charsets.ISO_8859_1)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '(' -> {
                    val sb = StringBuilder()
                    var j = i + 1
                    var depth = 1
                    while (j < s.length && depth > 0) {
                        val ch = s[j]
                        if (ch == '\\' && j + 1 < s.length) {
                            when (val n = s[j + 1]) {
                                'n' -> sb.append('\n')
                                'r' -> sb.append('\r')
                                't' -> sb.append('\t')
                                '(', ')', '\\' -> sb.append(n)
                                else -> sb.append(n)
                            }
                            j += 2
                        } else if (ch == '(') {
                            depth++
                            sb.append(ch)
                            j++
                        } else if (ch == ')') {
                            depth--
                            if (depth > 0) sb.append(ch)
                            j++
                        } else {
                            sb.append(ch)
                            j++
                        }
                    }
                    if (sb.isNotEmpty()) out.append(sb).append(' ')
                    i = j
                }
                s.startsWith("T*", i) || s.startsWith("Td", i) || s.startsWith("TD", i) -> {
                    out.append('\n')
                    i += 2
                }
                else -> i++
            }
        }
    }
}
