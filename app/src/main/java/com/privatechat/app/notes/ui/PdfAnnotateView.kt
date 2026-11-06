package com.privatechat.app.notes.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Draws on top of a PDF page: freehand pen strokes and stamped text.
 * Everything is stored in the page bitmap's own coordinates, so the
 * marks survive page switches and land in exactly the right place
 * when the edited PDF is saved. The page bitmap is drawn fit-centered,
 * exactly like the ImageView this view replaces while editing.
 *
 * Honest limit: this ADDS marks on top of a page. The PDF's own
 * printed text cannot be changed or erased — it is fixed in the file.
 */
class PdfAnnotateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    sealed class Element {
        data class Stroke(val points: List<PointF>, val color: Int, val widthPx: Float) : Element()
        data class TextMark(val x: Float, val y: Float, val text: String, val sizePx: Float, val color: Int) : Element()
    }

    enum class Mode { DRAW, TEXT }

    var mode: Mode = Mode.DRAW
    var penColor: Int = Color.BLACK
    var penWidthPx: Float = 6f
    var textSizePx: Float = 42f
    var textColor: Int = Color.BLACK

    /** Called (in bitmap coordinates) when the user taps in TEXT mode. */
    var onTextTap: ((x: Float, y: Float) -> Unit)? = null

    private var bitmap: Bitmap? = null
    private val elements = mutableListOf<Element>()
    private var currentStroke: MutableList<PointF>? = null

    init {
        isClickable = true
    }

    fun setPage(bmp: Bitmap?, elems: List<Element>) {
        bitmap = bmp
        elements.clear()
        elements.addAll(elems)
        currentStroke = null
        invalidate()
    }

    fun getElements(): List<Element> = elements.toList()

    fun addText(x: Float, y: Float, text: String) {
        if (text.isBlank()) return
        elements.add(Element.TextMark(x, y, text, textSizePx, textColor))
        invalidate()
    }

    fun undo() {
        if (elements.isNotEmpty()) {
            elements.removeAt(elements.lastIndex)
            invalidate()
        }
    }

    fun clearAll() {
        elements.clear()
        currentStroke = null
        invalidate()
    }

    /** Scale + offsets mapping the bitmap onto this view (fit-center). */
    private fun transform(): Triple<Float, Float, Float> {
        val bmp = bitmap ?: return Triple(1f, 0f, 0f)
        if (width == 0 || height == 0) return Triple(1f, 0f, 0f)
        val scale = minOf(width / bmp.width.toFloat(), height / bmp.height.toFloat())
        val dx = (width - bmp.width * scale) / 2f
        val dy = (height - bmp.height * scale) / 2f
        return Triple(scale, dx, dy)
    }

    private fun toBitmapCoords(x: Float, y: Float): PointF? {
        val bmp = bitmap ?: return null
        val (scale, dx, dy) = transform()
        val bx = (x - dx) / scale
        val by = (y - dy) / scale
        if (bx < 0 || by < 0 || bx > bmp.width || by > bmp.height) return null
        return PointF(bx, by)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        val (scale, dx, dy) = transform()
        val dst = RectF(dx, dy, dx + bmp.width * scale, dy + bmp.height * scale)
        canvas.drawBitmap(bmp, null, dst, null)
        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(scale, scale)
        drawElementsOnto(canvas, elements)
        currentStroke?.let { pts ->
            if (pts.isNotEmpty()) {
                drawStroke(canvas, Element.Stroke(pts.toList(), penColor, penWidthPx))
            }
        }
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (mode) {
            Mode.DRAW -> when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val p = toBitmapCoords(event.x, event.y) ?: return true
                    currentStroke = mutableListOf(p)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val stroke = currentStroke ?: return true
                    val p = toBitmapCoords(event.x, event.y) ?: return true
                    stroke.add(p)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val stroke = currentStroke
                    currentStroke = null
                    if (stroke != null && stroke.isNotEmpty()) {
                        elements.add(Element.Stroke(stroke.toList(), penColor, penWidthPx))
                    }
                    invalidate()
                    return true
                }
            }
            Mode.TEXT -> when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> return true
                MotionEvent.ACTION_UP -> {
                    val p = toBitmapCoords(event.x, event.y)
                    if (p != null) onTextTap?.invoke(p.x, p.y)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    companion object {
        /** Draws elements onto a canvas already in bitmap coordinates. */
        fun drawElementsOnto(canvas: Canvas, elements: List<Element>) {
            for (el in elements) {
                when (el) {
                    is Element.Stroke -> drawStroke(canvas, el)
                    is Element.TextMark -> {
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = el.color
                            textSize = el.sizePx
                        }
                        canvas.drawText(el.text, el.x, el.y, paint)
                    }
                }
            }
        }

        private fun drawStroke(canvas: Canvas, stroke: Element.Stroke) {
            if (stroke.points.isEmpty()) return
            if (stroke.points.size == 1) {
                val p = stroke.points[0]
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = stroke.color
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(p.x, p.y, stroke.widthPx / 2f, fill)
                return
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = stroke.color
                style = Paint.Style.STROKE
                strokeWidth = stroke.widthPx
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }
            val path = android.graphics.Path().apply {
                moveTo(stroke.points[0].x, stroke.points[0].y)
                for (i in 1 until stroke.points.size) {
                    lineTo(stroke.points[i].x, stroke.points[i].y)
                }
            }
            canvas.drawPath(path, paint)
        }
    }
}
