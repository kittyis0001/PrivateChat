package com.privatechat.app.notes.util

import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.privatechat.app.R

/**
 * Smart loading animation for the slow OK taps only: password verify,
 * PDF/image save, backup export/import. A spinning indicator in the
 * app's accent colour, no text at all. Shown while the slow work runs
 * and dismissed the moment it finishes. Instant actions never show it.
 */
object LoadingDialog {

    fun show(activity: AppCompatActivity): AlertDialog {
        val dp = activity.resources.displayMetrics.density
        val spinner = ProgressBar(activity).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(
                ContextCompat.getColor(activity, R.color.primary)
            )
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((36 * dp).toInt(), (28 * dp).toInt(), (36 * dp).toInt(), (28 * dp).toInt())
            addView(spinner)
        }
        return AlertDialog.Builder(activity)
            .setView(container)
            .setCancelable(false)
            .create()
            .also { it.show() }
    }
}
