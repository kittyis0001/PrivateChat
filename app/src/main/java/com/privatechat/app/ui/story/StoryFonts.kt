package com.privatechat.app.ui.story

import android.graphics.Typeface

/**
 * Same 5 font choices as story-editor.js's FONTS array (Classic/
 * Serif/Mono/Bold/Modern), mapped to Android's built-in Typeface
 * families since there's no need to bundle custom font files for
 * this set.
 */
object StoryFonts {

    data class FontOption(val id: String, val label: String)

    val ALL = listOf(
        FontOption("sans-serif", "Classic"),
        FontOption("serif", "Serif"),
        FontOption("monospace", "Mono"),
        FontOption("sans-serif-black", "Bold"),
        FontOption("sans-serif-condensed", "Modern")
    )

    fun typefaceFor(id: String): Typeface = when (id) {
        "serif" -> Typeface.SERIF
        "monospace" -> Typeface.MONOSPACE
        "sans-serif-black" -> Typeface.create("sans-serif-black", Typeface.BOLD)
        "sans-serif-condensed" -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        else -> Typeface.DEFAULT
    }
}
