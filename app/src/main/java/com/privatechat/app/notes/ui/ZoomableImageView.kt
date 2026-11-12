package com.privatechat.app.notes.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

/**
 * ImageView with pinch-to-zoom, drag-to-pan while zoomed, and
 * double-tap to toggle 1x/2x. A confirmed single tap is reported
 * through [onSingleTap] (the photo viewer uses it to close).
 *
 * No dependency: the zoom is a plain Matrix, so the view works in
 * the note photo preview and the PDF viewer alike. Setting a new
 * image (or a size change, e.g. rotation) resets the zoom to fit.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    /** Fired on a clean single tap (not a drag, not a pinch). */
    var onSingleTap: (() -> Unit)? = null

    private val matrix = Matrix()
    private var scale = 1f
    private val minScale = 1f
    private val maxScale = 4f
    private var viewW = 0
    private var viewH = 0
    private val lastPoint = PointF()

    private companion object {
        const val MODE_NONE = 0
        const val MODE_DRAG = 1
        const val MODE_PINCH = 2
    }

    private var mode = MODE_NONE

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newScale = (scale * detector.scaleFactor).coerceIn(minScale, maxScale)
                val applied = newScale / scale
                if (applied != 1f) {
                    matrix.postScale(applied, applied, detector.focusX, detector.focusY)
                    scale = newScale
                    fixBounds()
                    applyMatrix()
                }
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTap?.invoke()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val target = if (scale > 1.5f) minScale else 2f
                val factor = target / scale
                matrix.postScale(factor, factor, e.x, e.y)
                scale = target
                fixBounds()
                applyMatrix()
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewW = w
        viewH = h
        resetZoom()
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        resetZoom()
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        resetZoom()
    }

    /** Fits the current drawable into the view (FIT_CENTER) at scale 1. */
    private fun resetZoom() {
        matrix.reset()
        scale = 1f
        val d = drawable
        if (d != null && viewW > 0 && viewH > 0 &&
            d.intrinsicWidth > 0 && d.intrinsicHeight > 0
        ) {
            val fit = min(
                viewW.toFloat() / d.intrinsicWidth,
                viewH.toFloat() / d.intrinsicHeight
            )
            val dx = (viewW - d.intrinsicWidth * fit) / 2f
            val dy = (viewH - d.intrinsicHeight * fit) / 2f
            matrix.postScale(fit, fit)
            matrix.postTranslate(dx, dy)
        }
        applyMatrix()
    }

    /** Keeps the image covering the view: recentres it when it is
     *  smaller than the view, clamps its edges when it is larger. */
    private fun fixBounds() {
        val d = drawable ?: return
        if (viewW == 0 || viewH == 0) return
        val rect = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        matrix.mapRect(rect)
        var dx = 0f
        var dy = 0f
        if (rect.width() <= viewW) {
            dx = (viewW - rect.width()) / 2f - rect.left
        } else {
            if (rect.left > 0) dx = -rect.left
            if (rect.right < viewW) dx = viewW - rect.right
        }
        if (rect.height() <= viewH) {
            dy = (viewH - rect.height()) / 2f - rect.top
        } else {
            if (rect.top > 0) dy = -rect.top
            if (rect.bottom < viewH) dy = viewH - rect.bottom
        }
        if (dx != 0f || dy != 0f) matrix.postTranslate(dx, dy)
    }

    private fun applyMatrix() {
        imageMatrix = matrix
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = MODE_DRAG
                lastPoint.set(event.x, event.y)
                parent?.requestDisallowInterceptTouchEvent(scale > 1f)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                mode = MODE_PINCH
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == MODE_DRAG && scale > 1f && event.pointerCount == 1) {
                    matrix.postTranslate(event.x - lastPoint.x, event.y - lastPoint.y)
                    fixBounds()
                    applyMatrix()
                    lastPoint.set(event.x, event.y)
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = MODE_NONE
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}
