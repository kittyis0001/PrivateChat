package com.privatechat.app.notes.ui

import android.content.Context
import android.graphics.Typeface
import android.widget.EditText

/**
 * Editor font: size (13/15/19sp, default 15 = the layout's size) and
 * family (default/sans/serif/mono — all built-in Android typefaces,
 * no font files, no download). Stored in the Notes preferences file.
 */
object EditorFont {
    private const val PREFS = "kitty_notes_prefs"
    private const val KEY_SIZE = "editor_font_size_sp"
    private const val KEY_FAMILY = "editor_font_family"

    const val SIZE_SMALL = 13
    const val SIZE_MEDIUM = 15
    const val SIZE_LARGE = 19

    fun getSize(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SIZE, SIZE_MEDIUM)

    fun setSize(context: Context, sp: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_SIZE, sp).apply()
    }

    fun getFamily(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_FAMILY, "default") ?: "default"

    fun setFamily(context: Context, family: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_FAMILY, family).apply()
    }

    fun typefaceFor(family: String): Typeface = when (family) {
        "sans" -> Typeface.SANS_SERIF
        "serif" -> Typeface.SERIF
        "mono" -> Typeface.MONOSPACE
        else -> Typeface.DEFAULT
    }

    /** Applies the saved font to an editor text field (size + family). */
    fun applyTo(context: Context, field: EditText, applySize: Boolean = true) {
        if (applySize) field.textSize = getSize(context).toFloat()
        field.typeface = typefaceFor(getFamily(context))
    }
}
