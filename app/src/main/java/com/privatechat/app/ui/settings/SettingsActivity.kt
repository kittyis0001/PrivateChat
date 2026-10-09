package com.privatechat.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.privatechat.app.BuildConfig
import com.privatechat.app.R
import com.privatechat.app.data.AccessGate
import com.privatechat.app.data.Session
import com.privatechat.app.ui.login.LoginActivity

/**
 * Notes-inspired settings screen.
 *
 * Every row is wired to real functionality: the dark-mode switch uses
 * the same Session + AppCompatDelegate path as ChatActivity's menu,
 * notification/language rows open the corresponding system settings,
 * "Lock app now" clears only the process-local AccessGate (the real
 * login session is untouched until Log Out), and feedback/contact/rate/
 * share use standard Android intents. No fake buttons.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<ImageButton>(R.id.settingsBackButton).setOnClickListener { finish() }

        // Dark mode — same storage and delegate call as ChatActivity.toggleDarkTheme()
        val darkSwitch = findViewById<Switch>(R.id.darkModeSwitch)
        darkSwitch.isChecked = Session.isDarkThemeEnabled()
        darkSwitch.setOnCheckedChangeListener { _, isChecked ->
            Session.setDarkThemeEnabled(isChecked)
            AppCompatDelegate.setDefaultNightMode(
                if (isChecked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
        }
        findViewById<View>(R.id.rowDarkMode).setOnClickListener { darkSwitch.toggle() }

        // App version
        findViewById<TextView>(R.id.appVersionValue).text = BuildConfig.VERSION_NAME

        // Notifications -> system app notification settings
        findViewById<View>(R.id.rowNotifications).setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
                startActivity(intent)
            } catch (_: Exception) {
                openAppDetailsSettings()
            }
        }

        // Language -> system locale settings
        findViewById<View>(R.id.rowLanguage).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_LOCALE_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "Language follows your device settings", Toast.LENGTH_SHORT).show()
            }
        }

        // App lock
        findViewById<View>(R.id.rowAppLock).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Lock app now?")
                .setMessage("You will need to enter the access code again. You will stay signed in.")
                .setPositiveButton("Lock") { _, _ ->
                    AccessGate.lock()
                    val intent = Intent(this, LoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(intent)
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Privacy policy — in-app text (no invented external URL)
        findViewById<View>(R.id.rowPrivacy).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Privacy Policy")
                .setMessage("This app is for private communication between two authorised users. Messages and profile data are stored in the configured Firebase Realtime Database and media in Cloudinary, as set up by the app owner. The access code screen is an extra access step and does not replace your username and password login.")
                .setPositiveButton("OK", null)
                .show()
        }

        // Feedback / Contact — mail intents
        findViewById<View>(R.id.rowFeedback).setOnClickListener {
            sendMail("Private Chat feedback", "Hi,\n\n")
        }
        findViewById<View>(R.id.rowContact).setOnClickListener {
            sendMail("Private Chat support", "Hi,\n\n")
        }

        // Rate
        findViewById<View>(R.id.rowRate).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
                } catch (_: Exception) {
                    Toast.makeText(this, "Rating is not available yet", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Share
        findViewById<View>(R.id.rowShare).setOnClickListener {
            val text = "Private Chat — a private chat app for two."
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(intent, "Share app"))
        }

        // Log out — mirrors ChatActivity.performLogout(), plus re-locks the gate
        findViewById<View>(R.id.rowLogOut).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Log out?")
                .setMessage("You'll need to log in again to open this chat.")
                .setPositiveButton("Log Out") { _, _ ->
                    Session.clear()
                    AccessGate.lock()
                    val intent = Intent(this, LoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(intent)
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Reserved for future per-app language API; current row uses system settings.
        }
    }

    private fun sendMail(subject: String, body: String) {
        try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "No email app found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAppDetailsSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open settings", Toast.LENGTH_SHORT).show()
        }
    }
}
