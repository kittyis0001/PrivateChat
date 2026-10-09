package com.privatechat.app.notes.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.privatechat.app.BuildConfig
import com.privatechat.app.R
import com.privatechat.app.data.Session
import com.privatechat.app.notes.security.NotesLockManager

/**
 * Notes settings. Notes-scoped choices (accent, lock, language) live
 * in the Notes preferences. The Dark Mode switch drives the shared
 * AppCompatDelegate night mode for immediate effect; the chat app's
 * own saved theme preference (Session) is not overwritten here, and
 * App.onCreate re-applies the chat preference on cold start.
 */
class NotesSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notes_settings)

        findViewById<View>(R.id.notesSettingsBack).setOnClickListener { finish() }

        // Accent color
        findViewById<TextView>(R.id.accentValue).text = NotesLockManager.getAccent(this)
        findViewById<View>(R.id.rowAccent).setOnClickListener {
            val accents = arrayOf("pink", "lavender", "mint", "sky")
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.notes_accent_title))
                .setItems(accents) { _, which ->
                    NotesLockManager.setAccent(this, accents[which])
                    findViewById<TextView>(R.id.accentValue).text = accents[which]
                    Toast.makeText(this, getString(R.string.notes_accent_applied), Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        // Dark mode (shared night mode; chat Session preference untouched)
        val darkSwitch = findViewById<Switch>(R.id.notesDarkSwitch)
        darkSwitch.isChecked = Session.isDarkThemeEnabled() ||
            AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES
        darkSwitch.setOnCheckedChangeListener { _, isChecked ->
            AppCompatDelegate.setDefaultNightMode(
                if (isChecked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
        }
        findViewById<View>(R.id.rowNotesDark).setOnClickListener { darkSwitch.toggle() }

        // Language: English / Bengali / System (AppCompat per-app locales)
        findViewById<View>(R.id.rowNotesLanguage).setOnClickListener {
            val labels = arrayOf("System default", "English", "বাংলা")
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.notes_language_title))
                .setItems(labels) { _, which ->
                    val locales = when (which) {
                        1 -> LocaleListCompat.forLanguageTags("en")
                        2 -> LocaleListCompat.forLanguageTags("bn")
                        else -> LocaleListCompat.getEmptyLocaleList()
                    }
                    AppCompatDelegate.setApplicationLocales(locales)
                }
                .show()
        }

        // Version
        findViewById<TextView>(R.id.notesVersionValue).text = BuildConfig.VERSION_NAME

        // Share app
        findViewById<View>(R.id.rowNotesShare).setOnClickListener {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, getString(R.string.notes_share_app_text))
            }
            startActivity(Intent.createChooser(intent, getString(R.string.notes_share_note)))
        }

        // Privacy
        findViewById<View>(R.id.rowNotesPrivacy).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.notes_privacy_title))
                .setMessage(getString(R.string.notes_privacy_body))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        // Feedback
        findViewById<View>(R.id.rowNotesFeedback).setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:")
                    putExtra(Intent.EXTRA_SUBJECT, "Kitty Notes feedback")
                    putExtra(Intent.EXTRA_TEXT, "")
                }
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(this, getString(R.string.notes_no_email_app), Toast.LENGTH_SHORT).show()
            }
        }

        // About
        findViewById<View>(R.id.rowNotesAbout).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        // Lock
        refreshLockRow()
        findViewById<View>(R.id.rowNotesLock).setOnClickListener { showLockDialog() }
    }

    private fun refreshLockRow() {
        findViewById<TextView>(R.id.notesLockValue).text =
            if (NotesLockManager.isLockEnabled(this)) getString(R.string.notes_lock_on) else getString(R.string.notes_lock_off)
    }

    private fun showLockDialog() {
        if (!NotesLockManager.isLockEnabled(this)) {
            // Enable: ask new password + confirm
            val pw = EditText(this).apply {
                hint = getString(R.string.notes_lock_new_hint)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            val confirm = EditText(this).apply {
                hint = getString(R.string.notes_lock_confirm_hint)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(56, 24, 56, 8)
                addView(pw); addView(confirm)
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.notes_lock_set_title))
                .setMessage(getString(R.string.notes_lock_scope_note))
                .setView(container)
                .setPositiveButton(getString(R.string.notes_enable)) { _, _ ->
                    val a = pw.text.toString(); val b = confirm.text.toString()
                    when {
                        a.length < 4 -> Toast.makeText(this, getString(R.string.notes_lock_too_short), Toast.LENGTH_SHORT).show()
                        a != b -> Toast.makeText(this, getString(R.string.notes_lock_mismatch), Toast.LENGTH_SHORT).show()
                        else -> {
                            NotesLockManager.setPassword(this, a)
                            Toast.makeText(this, getString(R.string.notes_lock_enabled), Toast.LENGTH_SHORT).show()
                            refreshLockRow()
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            // Enabled: verify current to disable or change
            val current = EditText(this).apply {
                hint = getString(R.string.notes_lock_current_hint)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(56, 24, 56, 8)
                addView(current)
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.notes_lock_manage_title))
                .setView(container)
                .setPositiveButton(getString(R.string.notes_lock_disable)) { _, _ ->
                    if (NotesLockManager.verify(this, current.text.toString())) {
                        NotesLockManager.disableLock(this)
                        Toast.makeText(this, getString(R.string.notes_lock_disabled), Toast.LENGTH_SHORT).show()
                        refreshLockRow()
                    } else {
                        Toast.makeText(this, getString(R.string.notes_lock_wrong), Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}
