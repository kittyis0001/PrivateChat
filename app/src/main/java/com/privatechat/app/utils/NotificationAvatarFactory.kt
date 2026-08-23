package com.privatechat.app.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * This app has no profile-photo feature anywhere (no upload, no
 * stored URL) — there's nothing to fetch a real sender avatar from.
 * Rather than skip the "sender avatar" requirement or invent a photo
 * pipeline that wasn't asked for, this draws the same kind of
 * colored-circle-with-initial avatar WhatsApp itself shows for a
 * contact with no photo.
 */
object NotificationAvatarFactory {

    private const val SIZE_DP = 64

    fun create(density: Float, initial: Char, backgroundColor: Int): Bitmap {
        val size = (SIZE_DP * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = backgroundColor }
        val radius = size / 2f
        canvas.drawCircle(radius, radius, radius, circlePaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.45f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val textY = radius - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(initial.uppercaseChar().toString(), radius, textY, textPaint)

        return bitmap
    }
}
