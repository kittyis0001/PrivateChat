package com.privatechat.app.ui.story

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView
import com.privatechat.app.data.model.StoryStickerOverlay
import com.privatechat.app.data.model.StoryTextOverlay

/**
 * Holds the draggable text/sticker layer during editing — same role
 * as story-editor.js's .se-overlay-item elements inside
 * #storyEditorWrap. Drag repositioning is a plain touch listener per
 * child (matches the reference's own touchmove-based dragging, just
 * expressed as Android margins instead of CSS left/top percentages).
 */
class StoryOverlayContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private var selected: TextView? = null

    fun addText(text: String, font: String, sizeSp: Float, color: String, bg: String, anim: String = "none"): TextView {
        val view = TextView(context).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(Color.parseColor(color))
            typeface = StoryFonts.typefaceFor(font)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = if (bg != "transparent") {
                GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(parseBg(bg))
                }
            } else null
            setShadowLayer(4f, 0f, 1f, 0x80000000.toInt())
            tag = OverlayTag(kind = "text", font = font, bg = bg, anim = anim)
        }
        addOverlayChild(view, xPct = 50f, yPct = 40f)
        return view
    }

    fun addSticker(emoji: String): TextView {
        val view = TextView(context).apply {
            text = emoji
            textSize = 40f
            tag = OverlayTag(kind = "sticker")
        }
        addOverlayChild(view, xPct = 50f, yPct = 50f)
        return view
    }

    private fun addOverlayChild(view: TextView, xPct: Float, yPct: Float) {
        view.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        addView(view)
        view.post {
            positionByPercent(view, xPct, yPct)
            attachDrag(view)
        }
        selected = view
    }

    fun removeSelected() {
        selected?.let { removeView(it) }
        selected = null
    }

    fun hasSelection(): Boolean = selected != null

    private fun positionByPercent(view: TextView, xPct: Float, yPct: Float) {
        val lp = view.layoutParams as LayoutParams
        lp.leftMargin = ((xPct / 100f) * width - view.width / 2f).toInt()
        lp.topMargin = ((yPct / 100f) * height - view.height / 2f).toInt()
        view.layoutParams = lp
    }

    private fun attachDrag(view: TextView) {
        var startRawX = 0f
        var startRawY = 0f
        var startMarginX = 0
        var startMarginY = 0
        view.setOnTouchListener { v, event ->
            val lp = v.layoutParams as LayoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    selected = view
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startMarginX = lp.leftMargin
                    startMarginY = lp.topMargin
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    lp.leftMargin = (startMarginX + dx).toInt()
                        .coerceIn(-v.width / 2, width - v.width / 2)
                    lp.topMargin = (startMarginY + dy).toInt()
                        .coerceIn(-v.height / 2, height - v.height / 2)
                    v.layoutParams = lp
                    true
                }
                else -> true
            }
        }
    }

    fun exportTexts(): List<StoryTextOverlay> {
        val list = mutableListOf<StoryTextOverlay>()
        for (i in 0 until childCount) {
            val child = getChildAt(i) as? TextView ?: continue
            val info = child.tag as? OverlayTag ?: continue
            if (info.kind != "text") continue
            val lp = child.layoutParams as LayoutParams
            val cx = lp.leftMargin + child.width / 2f
            val cy = lp.topMargin + child.height / 2f
            list.add(
                StoryTextOverlay(
                    text = child.text.toString(),
                    xPct = if (width > 0) cx / width * 100f else 50f,
                    yPct = if (height > 0) cy / height * 100f else 50f,
                    font = info.font,
                    size = child.textSize / resources.displayMetrics.scaledDensity,
                    color = String.format("#%06X", 0xFFFFFF and child.currentTextColor),
                    bg = info.bg,
                    anim = info.anim
                )
            )
        }
        return list
    }

    fun exportStickers(): List<StoryStickerOverlay> {
        val list = mutableListOf<StoryStickerOverlay>()
        for (i in 0 until childCount) {
            val child = getChildAt(i) as? TextView ?: continue
            val info = child.tag as? OverlayTag ?: continue
            if (info.kind != "sticker") continue
            val lp = child.layoutParams as LayoutParams
            val cx = lp.leftMargin + child.width / 2f
            val cy = lp.topMargin + child.height / 2f
            list.add(
                StoryStickerOverlay(
                    emoji = child.text.toString(),
                    xPct = if (width > 0) cx / width * 100f else 50f,
                    yPct = if (height > 0) cy / height * 100f else 50f,
                    size = child.textSize / resources.displayMetrics.scaledDensity
                )
            )
        }
        return list
    }

    fun hasOverlays(): Boolean = childCount > 0

    /** Read-only playback of previously-saved text/sticker overlays —
     * used by the viewer to render a video story's overlay on top of
     * playback. No drag listener attached; each item gets its saved
     * entrance animation (fade/slide/zoom/none) instead. */
    fun loadFromEdit(texts: List<StoryTextOverlay>, stickers: List<StoryStickerOverlay>) {
        removeAllViews()
        texts.forEach { t ->
            val view = TextView(context).apply {
                text = t.text
                textSize = t.size
                setTextColor(try { Color.parseColor(t.color) } catch (e: IllegalArgumentException) { Color.WHITE })
                typeface = StoryFonts.typefaceFor(t.font)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = if (t.bg != "transparent") {
                    GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(parseBg(t.bg)) }
                } else null
                setShadowLayer(4f, 0f, 1f, 0x80000000.toInt())
                isClickable = false
            }
            addReadOnlyChild(view, t.xPct, t.yPct, t.anim)
        }
        stickers.forEach { s ->
            val view = TextView(context).apply {
                text = s.emoji
                textSize = s.size
                isClickable = false
            }
            addReadOnlyChild(view, s.xPct, s.yPct, "none")
        }
    }

    private fun addReadOnlyChild(view: TextView, xPct: Float, yPct: Float, anim: String) {
        view.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        view.alpha = 0f
        addView(view)
        view.post {
            positionByPercent(view, xPct, yPct)
            playEntranceAnimation(view, anim)
        }
    }

    private fun playEntranceAnimation(view: TextView, anim: String) {
        when (anim) {
            "slide" -> {
                view.translationY = dp(24).toFloat()
                view.alpha = 0f
                view.animate().translationY(0f).alpha(1f).setDuration(400).start()
            }
            "zoom" -> {
                view.scaleX = 0.5f
                view.scaleY = 0.5f
                view.alpha = 0f
                view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(400).start()
            }
            "fade" -> {
                view.alpha = 0f
                view.animate().alpha(1f).setDuration(500).start()
            }
            else -> view.alpha = 1f
        }
    }

    /** Bakes every text/sticker overlay directly onto [canvas] at its
     * actual pixel size — used when flattening an image story. */
    fun drawOnto(canvas: android.graphics.Canvas, targetWidth: Int, targetHeight: Int) {
        if (width == 0 || height == 0) return
        val scale = targetWidth.toFloat() / width
        for (i in 0 until childCount) {
            val child = getChildAt(i) as? TextView ?: continue
            val lp = child.layoutParams as LayoutParams
            val cx = (lp.leftMargin + child.width / 2f) * scale
            val cy = (lp.topMargin + child.height / 2f) * scale

            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = child.textSize * scale
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = child.typeface
                color = child.currentTextColor
            }
            val info = child.tag as? OverlayTag
            if (info?.kind == "text" && info.bg != "transparent") {
                val metrics = paint.measureText(child.text.toString())
                val padX = dp(10) * scale
                val padY = dp(6) * scale
                val bgPaint = android.graphics.Paint().apply { color = parseBg(info.bg) }
                canvas.drawRoundRect(
                    cx - metrics / 2 - padX,
                    cy - paint.textSize / 2 - padY,
                    cx + metrics / 2 + padX,
                    cy + paint.textSize / 2 + padY,
                    dp(8) * scale, dp(8) * scale, bgPaint
                )
            }
            paint.setShadowLayer(4f * scale, 0f, scale, 0x80000000.toInt())
            val fm = paint.fontMetrics
            val baseline = cy - (fm.ascent + fm.descent) / 2f
            canvas.drawText(child.text.toString(), cx, baseline, paint)
        }
    }

    private fun parseBg(bg: String): Int = try {
        Color.parseColor(bg)
    } catch (e: IllegalArgumentException) {
        Color.TRANSPARENT
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class OverlayTag(val kind: String, val font: String = "sans-serif", val bg: String = "transparent", val anim: String = "none")
}
