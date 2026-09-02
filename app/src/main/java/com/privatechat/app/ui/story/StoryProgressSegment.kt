package com.privatechat.app.ui.story

import android.animation.ValueAnimator
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout

/**
 * One segment of the viewer's top progress-bar row — a track behind a
 * fill that animates left-to-right over the story's duration, same
 * look as Instagram/the reference's .story-progress-bar-fill. One of
 * these per story in the currently open group.
 */
class StoryProgressSegment(context: Context) : FrameLayout(context) {

    private val fill = View(context)
    private var animator: ValueAnimator? = null

    init {
        val density = resources.displayMetrics.density
        val height = (2.5f * density).toInt()
        layoutParams = android.widget.LinearLayout.LayoutParams(0, height, 1f).apply {
            marginStart = (2 * density).toInt()
            marginEnd = (2 * density).toInt()
        }
        setBackgroundColor(0x4DFFFFFF)

        fill.setBackgroundColor(0xFFFFFFFF.toInt())
        fill.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.START
        }
        fill.pivotX = 0f
        fill.scaleX = 0f
        addView(fill)
    }

    /** Empty (not yet reached). */
    fun reset() {
        animator?.cancel()
        fill.scaleX = 0f
    }

    /** Fully filled (already played). */
    fun complete() {
        animator?.cancel()
        fill.scaleX = 1f
    }

    /** Animates the fill over [durationMs], calling [onFinished] when it
     * completes naturally (not when cancelled/paused). */
    fun start(durationMs: Long, onFinished: () -> Unit) {
        animator?.cancel()
        val startFraction = fill.scaleX
        val remaining = (durationMs * (1f - startFraction)).toLong().coerceAtLeast(1)
        animator = ValueAnimator.ofFloat(startFraction, 1f).apply {
            duration = remaining
            addUpdateListener { fill.scaleX = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (fill.scaleX >= 0.999f) onFinished()
                }
            })
            start()
        }
    }

    fun pause() {
        animator?.pause()
    }

    fun resume() {
        animator?.resume()
    }
}
