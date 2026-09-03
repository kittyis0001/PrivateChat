package com.privatechat.app.ui.story

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.privatechat.app.data.model.StoryDrawStroke
import com.privatechat.app.data.model.StoryPoint

/**
 * Same role as story-editor.js's drawCanvas/drawCtx + drawStrokes
 * array — a transparent overlay the user finger-paints on. Strokes
 * are kept as normalized (0-100%) points so they scale correctly to
 * whatever size the final image is baked at (or the viewer's video
 * surface is, for a video story).
 */
class StoryDrawView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Set to false while a different tool (text/sticker/filter) is
     * active, so drag gestures on this layer don't accidentally draw. */
    var drawingEnabled: Boolean = false

    var color: Int = Color.parseColor("#ff3b30")
    var strokeWidthDp: Float = 4f

    private data class Stroke(val paint: Paint, val path: Path, val points: MutableList<PointF>)
    private data class PointF(val x: Float, val y: Float)

    private val strokes = mutableListOf<Stroke>()
    private var current: Stroke? = null
    private val density = resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        strokes.forEach { canvas.drawPath(it.path, it.paint) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!drawingEnabled) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    color = this@StoryDrawView.color
                    strokeWidth = strokeWidthDp * density
                }
                val path = Path().apply { moveTo(event.x, event.y) }
                val stroke = Stroke(paint, path, mutableListOf(PointF(event.x, event.y)))
                current = stroke
                strokes.add(stroke)
            }
            MotionEvent.ACTION_MOVE -> {
                current?.let {
                    it.path.lineTo(event.x, event.y)
                    it.points.add(PointF(event.x, event.y))
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                current = null
            }
        }
        invalidate()
        return true
    }

    fun undo() {
        if (strokes.isNotEmpty()) {
            strokes.removeAt(strokes.size - 1)
            invalidate()
        }
    }

    fun clear() {
        strokes.clear()
        invalidate()
    }

    fun hasStrokes(): Boolean = strokes.isNotEmpty()

    /** Read-only playback of previously-saved normalized strokes — used
     * by the viewer to render a video story's draw overlay on top of
     * playback. drawingEnabled stays false so it's purely visual. */
    fun loadStrokes(saved: List<StoryDrawStroke>) {
        strokes.clear()
        post {
            if (width == 0 || height == 0) return@post
            saved.forEach { stroke ->
                if (stroke.points.isEmpty()) return@forEach
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    color = try { Color.parseColor(stroke.color) } catch (e: IllegalArgumentException) { Color.WHITE }
                    strokeWidth = stroke.size * density
                }
                val path = Path()
                val first = stroke.points[0]
                path.moveTo(first.xPct / 100f * width, first.yPct / 100f * height)
                stroke.points.drop(1).forEach {
                    path.lineTo(it.xPct / 100f * width, it.yPct / 100f * height)
                }
                strokes.add(Stroke(paint, path, mutableListOf()))
            }
            invalidate()
        }
    }

    /** Normalized (0-100%) export for a video story's edit-data —
     * mirrors story-editor.js's storyEditorExportEditData drawStrokes. */
    fun exportStrokes(): List<StoryDrawStroke> {
        if (width == 0 || height == 0) return emptyList()
        return strokes.map { stroke ->
            StoryDrawStroke(
                color = String.format("#%06X", 0xFFFFFF and stroke.paint.color),
                size = stroke.paint.strokeWidth / density,
                points = stroke.points.map { StoryPoint(it.x / width * 100f, it.y / height * 100f) }
            )
        }
    }

    /** Bakes the strokes directly onto [canvas] at its actual pixel
     * size — used when flattening an image story before upload. */
    fun drawOnto(canvas: Canvas, targetWidth: Int, targetHeight: Int) {
        if (width == 0 || height == 0) return
        val scaleX = targetWidth.toFloat() / width
        val scaleY = targetHeight.toFloat() / height
        strokes.forEach { stroke ->
            if (stroke.points.isEmpty()) return@forEach
            val scaledPaint = Paint(stroke.paint).apply {
                strokeWidth = stroke.paint.strokeWidth * maxOf(scaleX, scaleY)
            }
            val scaledPath = Path()
            scaledPath.moveTo(stroke.points[0].x * scaleX, stroke.points[0].y * scaleY)
            stroke.points.drop(1).forEach { scaledPath.lineTo(it.x * scaleX, it.y * scaleY) }
            canvas.drawPath(scaledPath, scaledPaint)
        }
    }
}
