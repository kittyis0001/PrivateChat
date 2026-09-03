package com.privatechat.app.ui.story

import android.graphics.Bitmap

/**
 * Plain-Kotlin box blur (3 passes ≈ a good approximation of a
 * Gaussian blur) — used to bake the Adjust panel's Blur slider into
 * the final exported image. No RenderScript (deprecated) or external
 * library needed. Only used at final export time, not for the live
 * editing preview (see StoryUploadActivity's own comment on that
 * trade-off) — so its cost is a one-off on Share, not per slider tick.
 */
object StoryBlur {

    /** [radiusPx] 0 = no-op passthrough. */
    fun apply(source: Bitmap, radiusPx: Int): Bitmap {
        if (radiusPx <= 0) return source
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        val radius = radiusPx.coerceAtMost(40)
        repeat(3) {
            boxBlurHorizontal(pixels, w, h, radius)
            boxBlurVertical(pixels, w, h, radius)
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }

    private fun boxBlurHorizontal(pixels: IntArray, w: Int, h: Int, radius: Int) {
        val row = IntArray(w)
        for (y in 0 until h) {
            val rowStart = y * w
            for (x in 0 until w) row[x] = pixels[rowStart + x]
            for (x in 0 until w) {
                var a = 0; var r = 0; var g = 0; var b = 0; var count = 0
                for (dx in -radius..radius) {
                    val sx = x + dx
                    if (sx in 0 until w) {
                        val p = row[sx]
                        a += (p ushr 24) and 0xFF
                        r += (p ushr 16) and 0xFF
                        g += (p ushr 8) and 0xFF
                        b += p and 0xFF
                        count++
                    }
                }
                pixels[rowStart + x] = ((a / count) shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
            }
        }
    }

    private fun boxBlurVertical(pixels: IntArray, w: Int, h: Int, radius: Int) {
        val col = IntArray(h)
        for (x in 0 until w) {
            for (y in 0 until h) col[y] = pixels[y * w + x]
            for (y in 0 until h) {
                var a = 0; var r = 0; var g = 0; var b = 0; var count = 0
                for (dy in -radius..radius) {
                    val sy = y + dy
                    if (sy in 0 until h) {
                        val p = col[sy]
                        a += (p ushr 24) and 0xFF
                        r += (p ushr 16) and 0xFF
                        g += (p ushr 8) and 0xFF
                        b += p and 0xFF
                        count++
                    }
                }
                pixels[y * w + x] = ((a / count) shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
            }
        }
    }
}
