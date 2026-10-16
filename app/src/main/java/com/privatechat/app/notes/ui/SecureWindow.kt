package com.privatechat.app.notes.ui

import android.app.Activity
import android.app.Dialog
import android.view.WindowManager

/**
 * Screenshot / screen-recording protection for private Notes screens.
 *
 * Sets [WindowManager.LayoutParams.FLAG_SECURE] so the window's content is
 * excluded from screenshots, the recent-apps overview, screen recordings
 * and casting (those capture paths see black instead).
 *
 * Call [protect] for an Activity as the first thing in onCreate (right after
 * super.onCreate, before setContentView) so there is no unprotected frame.
 * Dialogs are separate windows — call [protect] on any dialog that shows
 * private content.
 *
 * This is a platform mechanism, not a guarantee: an external camera, a
 * rooted/modified OS, or an unsupported capture path can still bypass it.
 */
object SecureWindow {

    fun protect(activity: Activity) {
        activity.window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
    }

    fun protect(dialog: Dialog) {
        dialog.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
    }
}
