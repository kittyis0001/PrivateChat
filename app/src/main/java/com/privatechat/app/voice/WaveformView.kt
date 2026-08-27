package com.privatechat.app.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * A row of vertical bars, each representing one amplitude sample,
 * with a "played" portion (up to [progress]) drawn in [playedColor]
 * and the rest in [unplayedColor] — the same visual language
 * WhatsApp uses for a voice message's waveform, both while recording
 * and during playback.
 *
 * This view only draws; it has no idea whether the amplitudes are
 * real (captured live from this device's own recording) or a stable
 * synthetic pattern (used for playback in a bubble, where the true
 * amplitude data was never transmitted — see MessageAdapter's
 * pseudoWaveform()). That distinction is the caller's responsibility.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var amplitudes: List<Float> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /** 0f..1f — fraction of the clip played so far (or recorded so far, while live). */
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var playedColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }
    var unplayedColor: Int = Color.parseColor("#66FFFFFF")
        set(value) {
            field = value
            invalidate()
        }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barWidthPx = 3f * resources.displayMetrics.density
    private val barGapPx = 2f * resources.displayMetrics.density
    private val minBarHeightPx = 3f * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val samples = amplitudes
        if (samples.isEmpty() || width == 0 || height == 0) return

        val barSpan = barWidthPx + barGapPx
        val maxBars = max(1, (width / barSpan).toInt())
        // Downsample to whatever actually fits this view's width, so a
        // long recording's amplitude list doesn't just get clipped —
        // it's evenly summarized across the available bars instead.
        val bars = if (samples.size <= maxBars) samples else downsample(samples, maxBars)

        val centerY = height / 2f
        val playedBars = (bars.size * progress).toInt()

        for (i in bars.indices) {
            val amplitude = bars[i].coerceIn(0f, 1f)
            val barHeight = max(minBarHeightPx, amplitude * height)
            val left = i * barSpan
            val right = left + barWidthPx
            barPaint.color = if (i < playedBars) playedColor else unplayedColor
            canvas.drawRoundRect(
                left, centerY - barHeight / 2f, right, centerY + barHeight / 2f,
                barWidthPx / 2f, barWidthPx / 2f, barPaint
            )
        }
    }

    private fun downsample(samples: List<Float>, targetCount: Int): List<Float> {
        val bucketSize = samples.size.toFloat() / targetCount
        return (0 until targetCount).map { bucketIndex ->
            val start = (bucketIndex * bucketSize).toInt()
            val end = min(samples.size, ((bucketIndex + 1) * bucketSize).toInt()).coerceAtLeast(start + 1)
            samples.subList(start, min(end, samples.size)).average().toFloat()
        }
    }
}
