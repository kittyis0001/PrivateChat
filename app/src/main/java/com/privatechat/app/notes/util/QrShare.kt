package com.privatechat.app.notes.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * QR share: a small note fits in one QR; a big note is split into
 * numbered parts ("KITTYQR|i/n|...") that the scanner reassembles.
 * Small-text QR works with no internet; the Wi-Fi leg for big notes
 * is handled by the editor (same phone-to-phone flow, QR carries
 * the parts). No server is involved.
 */
object QrShare {
    const val MAX_QR_CHARS = 2000
    private const val CHUNK = 1900
    private const val PREFIX = "KITTYQR"

    fun encodeToBitmap(text: String, size: Int): Bitmap? = runCatching {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        bitmap
    }.getOrNull()

    /** Splits a note into QR-sized parts, each prefixed "KITTYQR|i/n|". */
    fun splitForQr(text: String): List<String> {
        val total = (text.length + CHUNK - 1) / CHUNK
        if (total <= 1) return listOf(text)
        val parts = mutableListOf<String>()
        var start = 0
        var i = 1
        while (start < text.length) {
            val end = minOf(start + CHUNK, text.length)
            parts.add("$PREFIX|$i/$total|${text.substring(start, end)}")
            start = end
            i++
        }
        return parts
    }

    fun isQrPart(s: String): Boolean = s.startsWith("$PREFIX|")

    /** Parses "KITTYQR|i/n|..." into (i, n), or null when not a part. */
    fun parsePartHeader(s: String): Pair<Int, Int>? {
        if (!isQrPart(s)) return null
        val firstSep = s.indexOf('|', PREFIX.length + 1)
        if (firstSep < 0) return null
        val indexPart = s.substring(PREFIX.length + 1, firstSep)
        val slash = indexPart.indexOf('/')
        if (slash < 0) return null
        val i = indexPart.substring(0, slash).toIntOrNull() ?: return null
        val n = indexPart.substring(slash + 1).toIntOrNull() ?: return null
        return if (i in 1..n) i to n else null
    }

    /**
     * Stores a scanned part in [collected] (1-based slots, grown as
     * needed). Returns the full text once every part has arrived,
     * otherwise null. A plain (non-part) scan is returned as-is.
     * The caller clears [collected] after a full note is assembled
     * or when a new scan session starts.
     */
    fun tryAssemble(scanned: String, collected: MutableList<String>): String? {
        if (!isQrPart(scanned)) return scanned
        val firstSep = scanned.indexOf('|', PREFIX.length + 1)
        if (firstSep < 0) return null
        val indexPart = scanned.substring(PREFIX.length + 1, firstSep)
        val slash = indexPart.indexOf('/')
        if (slash < 0) return null
        val i = indexPart.substring(0, slash).toIntOrNull() ?: return null
        val n = indexPart.substring(slash + 1).toIntOrNull() ?: return null
        if (i !in 1..n || n > 99) return null
        val payload = scanned.substring(firstSep + 1)
        while (collected.size < n) collected.add("")
        collected[i - 1] = payload
        return if (collected.size == n && collected.all { it.isNotEmpty() }) {
            collected.joinToString("")
        } else {
            null
        }
    }
}
