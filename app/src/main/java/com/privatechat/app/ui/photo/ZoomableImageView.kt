package com.privatechat.app.ui.photo

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import androidx.appcompat.widget.AppCompatImageView

/**
 * Instagram-style full-image viewer behavior: pinch to zoom, double
 * tap to zoom, drag to pan while zoomed, and — when NOT zoomed in —
 * drag down to dismiss (fading and shrinking with the drag, snapping
 * back if released early, calling [onDismiss] if released past the
 * threshold or thrown fast enough). All via a single Matrix on a
 * plain ImageView; no extra library.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 5f
        const val DOUBLE_TAP_SCALE = 3f
        const val DISMISS_DRAG_THRESHOLD_PX = 240f
        const val DISMISS_FLING_VELOCITY = 1200f
    }

    // Reported while dragging (0f..1f, how far toward the dismiss
    // threshold) so the host can fade/scale the dark background in
    // sync with the drag, and once with true/false on release.
    var onDragProgress: ((progress: Float) -> Unit)? = null
    var onDismiss: (() -> Unit)? = null

    private val matrixValues = FloatArray(9)
    private val imageMatrix2 = Matrix()
    private var currentScale = MIN_SCALE

    private var isDragging = false
    private var dragTranslationY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var velocityTracker: VelocityTracker? = null

    init {
        scaleType = ScaleType.MATRIX
        imageMatrix = imageMatrix2
    }

    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val newScale = (currentScale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
            val factor = newScale / currentScale
            currentScale = newScale
            imageMatrix2.postScale(factor, factor, detector.focusX, detector.focusY)
            constrainTranslation()
            imageMatrix = imageMatrix2
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val targetScale = if (currentScale > MIN_SCALE + 0.1f) MIN_SCALE else DOUBLE_TAP_SCALE
            val factor = targetScale / currentScale
            currentScale = targetScale
            imageMatrix2.postScale(factor, factor, e.x, e.y)
            constrainTranslation()
            imageMatrix = imageMatrix2
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                activePointerId = event.getPointerId(0)
                velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex == -1 || event.pointerCount > 1) return true
                val dx = event.getX(pointerIndex) - lastTouchX
                val dy = event.getY(pointerIndex) - lastTouchY
                lastTouchX = event.getX(pointerIndex)
                lastTouchY = event.getY(pointerIndex)

                if (currentScale > MIN_SCALE + 0.01f) {
                    // Zoomed in — pan around the image, no dismiss drag.
                    imageMatrix2.postTranslate(dx, dy)
                    constrainTranslation()
                    imageMatrix = imageMatrix2
                } else if (dy > 0 || isDragging) {
                    // Not zoomed — a downward drag scrubs the
                    // swipe-down-to-dismiss gesture instead of panning
                    // the (already 1:1) image.
                    isDragging = true
                    dragTranslationY = (dragTranslationY + dy).coerceAtLeast(0f)
                    translationY = dragTranslationY
                    val progress = (dragTranslationY / DISMISS_DRAG_THRESHOLD_PX).coerceIn(0f, 1f)
                    onDragProgress?.invoke(progress)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    velocityTracker?.apply {
                        computeCurrentVelocity(1000)
                        val flingDown = yVelocity > DISMISS_FLING_VELOCITY
                        if (dragTranslationY > DISMISS_DRAG_THRESHOLD_PX || flingDown) {
                            onDismiss?.invoke()
                        } else {
                            snapBack()
                        }
                    } ?: snapBack()
                    isDragging = false
                    dragTranslationY = 0f
                }
                velocityTracker?.recycle()
                velocityTracker = null
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }
        }
        return true
    }

    private fun snapBack() {
        animate().translationY(0f).setDuration(200).start()
        onDragProgress?.invoke(0f)
    }

    // Keeps the image from being panned/zoomed past its own edges into
    // empty space — standard photo-viewer behavior.
    private fun constrainTranslation() {
        val drawableInstance = drawable ?: return
        imageMatrix2.getValues(matrixValues)
        val scaledWidth = drawableInstance.intrinsicWidth * matrixValues[Matrix.MSCALE_X]
        val scaledHeight = drawableInstance.intrinsicHeight * matrixValues[Matrix.MSCALE_Y]

        var dx: Float
        var dy: Float
        if (scaledWidth <= width) {
            dx = (width - scaledWidth) / 2f - matrixValues[Matrix.MTRANS_X]
        } else {
            val minX = width - scaledWidth
            dx = matrixValues[Matrix.MTRANS_X].coerceIn(minX, 0f) - matrixValues[Matrix.MTRANS_X]
        }
        if (scaledHeight <= height) {
            dy = (height - scaledHeight) / 2f - matrixValues[Matrix.MTRANS_Y]
        } else {
            val minY = height - scaledHeight
            dy = matrixValues[Matrix.MTRANS_Y].coerceIn(minY, 0f) - matrixValues[Matrix.MTRANS_Y]
        }
        imageMatrix2.postTranslate(dx, dy)
    }

    // Centers a freshly-loaded image (called once Glide delivers the
    // bitmap and the view has its final size) so it starts fully
    // visible and un-zoomed instead of anchored top-left.
    fun resetToCenter() {
        val drawableInstance = drawable ?: return
        if (width == 0 || height == 0) return
        val scale = minOf(
            width.toFloat() / drawableInstance.intrinsicWidth,
            height.toFloat() / drawableInstance.intrinsicHeight
        )
        currentScale = MIN_SCALE
        imageMatrix2.reset()
        imageMatrix2.postScale(scale, scale)
        val dx = (width - drawableInstance.intrinsicWidth * scale) / 2f
        val dy = (height - drawableInstance.intrinsicHeight * scale) / 2f
        imageMatrix2.postTranslate(dx, dy)
        imageMatrix = imageMatrix2
        translationY = 0f
    }
}
