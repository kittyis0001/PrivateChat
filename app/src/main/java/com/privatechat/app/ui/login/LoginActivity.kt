package com.privatechat.app.ui.login

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.messaging.FirebaseMessaging
import com.privatechat.app.R
import com.privatechat.app.data.AccessGate
import com.privatechat.app.data.Session
import com.privatechat.app.ui.chat.ChatActivity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton

/**
 * Validates credentials against a `users/{username}/password` node in
 * the existing Firebase Realtime Database, then persists the session
 * locally via Session so future app launches skip straight to chat —
 * this app has exactly two fixed accounts, so this simple check is
 * sufficient; it intentionally does not use Firebase Authentication's
 * full identity system, since there's no need for sign-up, password
 * reset, or more than two permanent users. If stronger security is
 * needed later, this is the file to swap for real Firebase Auth.
 *
 * NOTE: for a production app, passwords here should be stored hashed
 * (e.g. via a Cloud Function) rather than compared in plaintext — this
 * mirrors the simplicity of the original two-person setup but is
 * flagged here as a follow-up hardening item, not hidden.
 */
class LoginActivity : AppCompatActivity() {

    companion object {
        private const val KEY_GATE_UNLOCKED = "gate_unlocked"
    }

    // No-op result callback — notifications simply won't show if the user
    // declines; nothing else in the app depends on this being granted.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private lateinit var accessContainer: LinearLayout
    private lateinit var loginContainer: LinearLayout
    private lateinit var accessCodeInput: TextInputEditText
    private lateinit var accessErrorText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Restore gate state across configuration changes within the same
        // process. A fresh process starts locked because AccessGate lives
        // only in process memory and nothing is persisted.
        val wasUnlocked = savedInstanceState?.getBoolean(KEY_GATE_UNLOCKED, false) == true ||
            AccessGate.isUnlocked
        if (wasUnlocked) AccessGate.unlock()

        // Always inflate the layout first and keep the login form hidden
        // so the username/password fields never flash before the gate
        // has been passed.
        setContentView(R.layout.activity_login)
        accessContainer = findViewById(R.id.accessContainer)
        loginContainer = findViewById(R.id.loginContainer)
        accessCodeInput = findViewById(R.id.accessCodeInput)
        accessErrorText = findViewById(R.id.accessErrorText)

        requestNotificationPermissionIfNeeded()

        findViewById<ImageButton>(R.id.accessMenuButton).setOnClickListener { showAccessOverflow(it) }
        findViewById<MaterialButton>(R.id.accessContinueButton).setOnClickListener { onAccessContinue() }
        accessCodeInput.setOnEditorActionListener { _, _, _ ->
            onAccessContinue()
            true
        }

        if (AccessGate.isUnlocked) {
            showLoginAfterGate()
        } else {
            accessContainer.visibility = View.VISIBLE
            loginContainer.visibility = View.GONE
            accessErrorText.visibility = View.GONE
        }

        val usernameInput = findViewById<TextInputEditText>(R.id.usernameInput)
        val passwordInput = findViewById<TextInputEditText>(R.id.passwordInput)
        val loginButton = findViewById<MaterialButton>(R.id.loginButton)
        val progress = findViewById<ProgressBar>(R.id.loginProgress)

        loginButton.setOnClickListener {
            val username = usernameInput.text?.toString()?.trim().orEmpty()
            val password = passwordInput.text?.toString().orEmpty()

            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Enter username & password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            loginButton.isEnabled = false
            progress.visibility = View.VISIBLE

            FirebaseDatabase.getInstance("https://private-chat-7a103-default-rtdb.asia-southeast1.firebasedatabase.app").getReference("users").child(username)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val storedPassword = snapshot.child("password").getValue(String::class.java)
                        progress.visibility = View.GONE
                        loginButton.isEnabled = true

                        if (storedPassword != null && storedPassword == password) {
                            Session.save(username)
                            registerFcmToken(username)
                            goToChat()
                        } else {
                            Toast.makeText(this@LoginActivity, "Access denied", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        progress.visibility = View.GONE
                        loginButton.isEnabled = true
                        Toast.makeText(this@LoginActivity, "Connection error — try again", Toast.LENGTH_SHORT).show()
                    }
                })
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_GATE_UNLOCKED, AccessGate.isUnlocked)
    }

    private fun onAccessContinue() {
        val entered = accessCodeInput.text?.toString()?.trim().orEmpty()
        if (entered == AccessGate.REQUIRED_CODE) {
            accessErrorText.visibility = View.GONE
            AccessGate.unlock()
            showLoginAfterGate()
        } else {
            accessErrorText.visibility = View.VISIBLE
            accessCodeInput.text?.clear()
            // Subtle shake via translation — no framework shake anim exists.
            accessContainer.animate()
                .translationX(10f).setDuration(50)
                .withEndAction {
                    accessContainer.animate()
                        .translationX(-10f).setDuration(50)
                        .withEndAction {
                            accessContainer.animate().translationX(0f).setDuration(50).start()
                        }
                        .start()
                }
                .start()
        }
    }

    /**
     * Called only after the access code has been accepted in this
     * process. Preserves the existing session behaviour: an already
     * logged-in user goes straight to chat, otherwise the original
     * username/password form is revealed unchanged.
     */
    private fun showLoginAfterGate() {
        accessContainer.visibility = View.GONE
        accessErrorText.visibility = View.GONE
        if (Session.isLoggedIn()) {
            goToChat()
            return
        }
        loginContainer.visibility = View.VISIBLE
        loginContainer.alpha = 0f
        loginContainer.animate().alpha(1f).setDuration(220).start()
    }

    private fun showAccessOverflow(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.apply {
            add(0, 1, 0, "About")
            add(0, 2, 1, "Privacy Policy")
            add(0, 3, 2, "App version 1.0")
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Kitty Notes")
                        .setMessage("Premium Secret Chat for two authorised users. Enter your access code, then sign in with your existing username and password.")
                        .setPositiveButton("OK", null)
                        .show()
                    true
                }
                2 -> {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Privacy Policy")
                        .setMessage("This app is for private communication between two authorised users. Messages and profile data are stored in the configured Firebase Realtime Database and media in Cloudinary, as set up by the app owner. The access code screen is an extra access step and does not replace your username and password login.")
                        .setPositiveButton("OK", null)
                        .show()
                    true
                }
                else -> true
            }
        }
        popup.show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun registerFcmToken(username: String) {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            FirebaseDatabase.getInstance("https://private-chat-7a103-default-rtdb.asia-southeast1.firebasedatabase.app").getReference("fcmTokens").child(username).setValue(token)
        }
    }

    private fun goToChat() {
        startActivity(Intent(this, ChatActivity::class.java))
        finish()
    }
}
