package com.privatechat.app.ui.story

import android.graphics.ColorMatrix

/**
 * Same 12 filters (11 + Normal) as story-editor.js's FILTERS array,
 * with the exact same brightness/contrast/saturate/hue-rotate/sepia/
 * grayscale values — just expressed as Android ColorMatrix
 * coefficients instead of CSS filter strings, since that's what a
 * native ImageView/Canvas can actually apply. Standard, well-
 * documented matrix formulas (CSS Filter Effects spec) are used for
 * hue-rotate and sepia so the math matches what a browser does.
 */
object StoryFilters {

    data class Filter(
        val id: String,
        val label: String,
        val brightness: Float = 1f,
        val contrast: Float = 1f,
        val saturation: Float = 1f,
        val hueRotateDeg: Float = 0f,
        val sepia: Float = 0f,
        val grayscale: Float = 0f
    )

    val ALL = listOf(
        Filter("none", "Normal"),
        Filter("clarendon", "Clarendon", brightness = 1.1f, contrast = 1.2f, saturation = 1.35f),
        Filter("gingham", "Gingham", brightness = 1.05f, hueRotateDeg = -10f, sepia = 0.08f),
        Filter("moon", "Moon", grayscale = 1f, brightness = 1.1f, contrast = 1.1f),
        Filter("lark", "Lark", brightness = 1.08f, contrast = 0.92f, saturation = 1.1f, sepia = 0.05f),
        Filter("reyes", "Reyes", brightness = 1.1f, contrast = 0.9f, saturation = 0.8f, sepia = 0.22f),
        Filter("juno", "Juno", brightness = 1.08f, contrast = 1.1f, saturation = 1.3f, hueRotateDeg = 5f),
        Filter("slumber", "Slumber", brightness = 0.95f, saturation = 0.85f, sepia = 0.2f),
        Filter("crema", "Crema", brightness = 1.05f, contrast = 0.95f, saturation = 0.9f, sepia = 0.15f),
        Filter("ludwig", "Ludwig", brightness = 1.05f, contrast = 1.08f, saturation = 1.1f),
        Filter("aden", "Aden", brightness = 1.05f, hueRotateDeg = -20f, saturation = 0.9f, sepia = 0.15f),
        Filter("perpetua", "Perpetua", brightness = 1.05f, contrast = 1.1f, saturation = 0.8f, sepia = 0.1f)
    )

    fun byId(id: String): Filter = ALL.find { it.id == id } ?: ALL[0]

    /**
     * Combines a filter with the separate Adjust-panel brightness/
     * contrast (both 50-150, 100 = no change) into one ColorMatrix
     * ready to hand to Paint.setColorFilter/ImageView.setColorFilter —
     * same combined effect as story-editor.js concatenating the
     * filter's CSS string with the adjust sliders' CSS.
     */
    fun buildColorMatrix(filter: Filter, adjustBrightnessPct: Int, adjustContrastPct: Int): ColorMatrix {
        val cm = ColorMatrix()
        val effectiveSaturation = (filter.saturation * (1f - filter.grayscale)).coerceIn(0f, 4f)
        if (effectiveSaturation != 1f) {
            cm.postConcat(ColorMatrix().apply { setSaturation(effectiveSaturation) })
        }
        if (filter.hueRotateDeg != 0f) {
            cm.postConcat(hueRotateMatrix(filter.hueRotateDeg))
        }
        if (filter.sepia > 0f) {
            cm.postConcat(sepiaMatrix(filter.sepia))
        }
        val totalBrightness = filter.brightness * (adjustBrightnessPct / 100f)
        val totalContrast = filter.contrast * (adjustContrastPct / 100f)
        cm.postConcat(brightnessContrastMatrix(totalBrightness, totalContrast))
        return cm
    }

    private fun brightnessContrastMatrix(brightness: Float, contrast: Float): ColorMatrix {
        // CSS brightness(x): scale RGB by x. CSS contrast(x): pivot
        // around mid-grey (128 in Android's 0-255 ColorMatrix space).
        // Applying brightness's scale first, then contrast's
        // scale+pivot on top, matches how the two combine in CSS
        // (chained filter functions).
        val b = ColorMatrix(
            floatArrayOf(
                brightness, 0f, 0f, 0f, 0f,
                0f, brightness, 0f, 0f, 0f,
                0f, 0f, brightness, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val translate = 128f * (1f - contrast)
        val c = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        b.postConcat(c)
        return b
    }

    /** Interpolates between identity and the standard CSS sepia(1) matrix by [amount]. */
    private fun sepiaMatrix(amount: Float): ColorMatrix {
        val a = amount.coerceIn(0f, 1f)
        val i = 1f - a
        return ColorMatrix(
            floatArrayOf(
                0.393f * a + i, 0.769f * a, 0.189f * a, 0f, 0f,
                0.349f * a, 0.686f * a + i, 0.168f * a, 0f, 0f,
                0.272f * a, 0.534f * a, 0.131f * a + i, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }

    /** Standard CSS/SVG hue-rotate matrix (W3C Filter Effects spec formula). */
    private fun hueRotateMatrix(degrees: Float): ColorMatrix {
        val rad = Math.toRadians(degrees.toDouble())
        val cos = Math.cos(rad).toFloat()
        val sin = Math.sin(rad).toFloat()
        return ColorMatrix(
            floatArrayOf(
                0.213f + cos * 0.787f - sin * 0.213f,
                0.715f - cos * 0.715f - sin * 0.715f,
                0.072f - cos * 0.072f + sin * 0.928f,
                0f, 0f,

                0.213f - cos * 0.213f + sin * 0.143f,
                0.715f + cos * 0.285f + sin * 0.140f,
                0.072f - cos * 0.072f - sin * 0.283f,
                0f, 0f,

                0.213f - cos * 0.213f - sin * 0.787f,
                0.715f - cos * 0.715f + sin * 0.715f,
                0.072f + cos * 0.928f + sin * 0.072f,
                0f, 0f,

                0f, 0f, 0f, 1f, 0f
            )
        )
    }
}
