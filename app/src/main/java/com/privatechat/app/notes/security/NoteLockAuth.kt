package com.privatechat.app.notes.security

import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.privatechat.app.R
import com.privatechat.app.notes.util.LoadingDialog
import kotlinx.coroutines.launch

/**
 * Per-note lock auth, shared by the home list and the editor.
 * A locked note is protected by the same Notes password / fingerprint
 * as the app lock (NotesLockManager) — one password to remember, never
 * a second one. Unlocks are process-local: relaunching the app
 * re-locks every note.
 */
object NoteLockAuth {

    private val unlockedNoteIds = mutableSetOf<Long>()

    fun isUnlocked(noteId: Long): Boolean = synchronized(unlockedNoteIds) { noteId in unlockedNoteIds }

    fun markUnlocked(noteId: Long) {
        synchronized(unlockedNoteIds) { unlockedNoteIds.add(noteId) }
    }

    fun markLocked(noteId: Long) {
        synchronized(unlockedNoteIds) { unlockedNoteIds.remove(noteId) }
    }

    /**
     * Runs [onReady] once a Notes password exists. When none is set yet,
     * asks the user to create one first (4+ characters, confirmed).
     * The dialog stays open until the password is valid.
     */
    fun ensurePassword(activity: AppCompatActivity, onReady: () -> Unit) {
        if (NotesLockManager.hasPassword(activity)) {
            onReady()
            return
        }
        val dp = activity.resources.displayMetrics.density
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), 0)
        }
        val password = EditText(activity).apply {
            hint = activity.getString(R.string.notes_lock_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val confirm = EditText(activity).apply {
            hint = activity.getString(R.string.notes_lock_confirm_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val error = TextView(activity).apply {
            setTextColor(ContextCompat.getColor(activity, android.R.color.holo_red_dark))
            textSize = 13f
            visibility = View.GONE
            setPadding(0, (8 * dp).toInt(), 0, 0)
        }
        container.addView(password)
        container.addView(confirm)
        container.addView(error)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.notes_lock_set_password_title))
            .setMessage(activity.getString(R.string.notes_lock_note_only))
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val pw = password.text.toString()
            val cf = confirm.text.toString()
            when {
                pw.length < 4 -> {
                    error.text = activity.getString(R.string.notes_lock_password_short)
                    error.visibility = View.VISIBLE
                }
                pw != cf -> {
                    error.text = activity.getString(R.string.notes_lock_password_mismatch)
                    error.visibility = View.VISIBLE
                }
                else -> {
                    // Creating a password here locks notes only — the
                    // whole-app lock stays off (enableAppLock = false).
                    val progress = LoadingDialog.show(activity)
                    activity.lifecycleScope.launch {
                        NotesLockManager.setPassword(activity, pw, enableAppLock = false)
                        progress.dismiss()
                        dialog.dismiss()
                        onReady()
                    }
                }
            }
        }
    }

    /**
     * Fingerprint-or-password dialog for one locked note. Calls
     * [onSuccess] only after the user proves they may read it.
     * The dialog stays open on a wrong password so the user can retry.
     */
    fun promptUnlock(activity: AppCompatActivity, onSuccess: () -> Unit) {
        val dp = activity.resources.displayMetrics.density
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), 0)
        }
        val password = EditText(activity).apply {
            hint = activity.getString(R.string.notes_lock_unlock_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val error = TextView(activity).apply {
            setTextColor(ContextCompat.getColor(activity, android.R.color.holo_red_dark))
            textSize = 13f
            visibility = View.GONE
            setPadding(0, (8 * dp).toInt(), 0, 0)
        }
        container.addView(password)
        container.addView(error)
        val builder = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.notes_lock_unlock_title))
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
        // Fingerprint: same availability check as the app lock screen
        // (hardware + enrolled fingerprint, API 28+).
        val fingerprintAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            runCatching {
                @Suppress("DEPRECATION")
                androidx.core.hardware.fingerprint.FingerprintManagerCompat.from(activity)
                    .let { it.isHardwareDetected && it.hasEnrolledFingerprints() }
            }.getOrDefault(false)
        if (fingerprintAvailable) {
            builder.setNeutralButton(activity.getString(R.string.notes_lock_fingerprint), null)
        }
        val dialog = builder.create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val pw = password.text.toString()
            if (pw.isEmpty()) {
                // Empty input never reaches PBKDF2: Android rejects an
                // empty password with an exception — show the error instead.
                error.text = activity.getString(R.string.notes_lock_wrong_password)
                error.visibility = View.VISIBLE
                return@setOnClickListener
            }
            val progress = LoadingDialog.show(activity)
            activity.lifecycleScope.launch {
                if (NotesLockManager.verify(activity, pw)) {
                    progress.dismiss()
                    dialog.dismiss()
                    onSuccess()
                } else {
                    progress.dismiss()
                    error.text = activity.getString(R.string.notes_lock_wrong_password)
                    error.visibility = View.VISIBLE
                    password.text.clear()
                }
            }
        }
        if (fingerprintAvailable) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                val executor = ContextCompat.getMainExecutor(activity)
                val prompt = BiometricPrompt.Builder(activity)
                    .setTitle(activity.getString(R.string.notes_fingerprint_prompt))
                    .setSubtitle(activity.getString(R.string.notes_fingerprint_subtitle))
                    .setNegativeButton(activity.getString(android.R.string.cancel), executor) { _, _ -> }
                    .build()
                prompt.authenticate(
                    android.os.CancellationSignal(),
                    executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                            dialog.dismiss()
                            onSuccess()
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                            // Cancel/lockout: the system prompt already
                            // explained it; the password path stays open.
                        }

                        override fun onAuthenticationFailed() {
                            error.text = activity.getString(R.string.notes_fingerprint_failed)
                            error.visibility = View.VISIBLE
                        }
                    }
                )
            }
        }
    }
}
