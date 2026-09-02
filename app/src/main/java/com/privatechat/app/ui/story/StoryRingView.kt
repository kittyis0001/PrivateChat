package com.privatechat.app.ui.story

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Same-to-same replica of the reference web app's ring CSS
 * (.sv2-ring-active / .sv2-ring-viewed / .sv2-ring-none in story.css):
 * a 9-stop conic gradient for an unviewed story, a flat grey sweep for
 * an already-viewed one, nothing for no story at all. Drawn with a
 * real SweepGradient (not a 3-stop XML <gradient>, which can't
 * reproduce Instagram's actual multi-stop ring) so the colors and
 * proportions match exactly.
 *
 * Used as a plain sibling View sized a few dp larger than the avatar
 * it surrounds and placed behind it in a FrameLayout — it draws only
 * the ring stroke, never touches the avatar image itself, so avatar
 * loading (Glide, click listeners, etc.) elsewhere is completely
 * unaffected by adding this.
 */
class StoryRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class RingState { NONE, ACTIVE, VIEWED }

    var state: RingState = RingState.NONE
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val strokeWidthPx = 3f * resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (state == RingState.NONE) return

        val cx = width / 2f
        val cy = height / 2f
        val radius = (min(width, height) - strokeWidthPx) / 2f
        if (radius <= 0f) return

        paint.strokeWidth = strokeWidthPx
        paint.shader = when (state) {
            RingState.ACTIVE -> SweepGradient(cx, cy, ACTIVE_COLORS, ACTIVE_STOPS)
            RingState.VIEWED -> SweepGradient(cx, cy, VIEWED_COLORS, VIEWED_STOPS)
            RingState.NONE -> null
        }

        // CSS conic-gradient's 0% starts at 12 o'clock; Android's
        // SweepGradient starts at 3 o'clock. Rotating the canvas -90°
        // before drawing lines the two up so the ring's colors land in
        // the same positions as the reference design.
        canvas.save()
        canvas.rotate(-90f, cx, cy)
        canvas.drawCircle(cx, cy, radius, paint)
        canvas.restore()
    }

    companion object {
        // Exact stops from .sv2-ring-active's conic-gradient in story.css.
        private val ACTIVE_COLORS = intArrayOf(
            Color.parseColor("#f09433"),
            Color.parseColor("#e6683c"),
            Color.parseColor("#dc2743"),
            Color.parseColor("#cc2366"),
            Color.parseColor("#bc1888"),
            Color.parseColor("#8a3ab9"),
            Color.parseColor("#4c68d7"),
            Color.parseColor("#cd486b"),
            Color.parseColor("#f09433")
        )
        private val ACTIVE_STOPS = floatArrayOf(0f, 0.12f, 0.25f, 0.37f, 0.50f, 0.62f, 0.75f, 0.87f, 1f)

        // Exact stops from .sv2-ring-viewed's conic-gradient.
        private val VIEWED_COLORS = intArrayOf(
            Color.parseColor("#a0a0a0"),
            Color.parseColor("#c8c8c8"),
            Color.parseColor("#a0a0a0")
        )
        private val VIEWED_STOPS = floatArrayOf(0f, 0.5f, 1f)
    }
}
