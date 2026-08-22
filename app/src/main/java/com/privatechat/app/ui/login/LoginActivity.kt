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
import com.privatechat.app.data.Session
import com.privatechat.app.ui.chat.ChatActivity
import android.view.View
import android.widget.ProgressBar
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

    // No-op result callback — notifications simply won't show if the user
    // declines; nothing else in the app depends on this being granted.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNotificationPermissionIfNeeded()

        // Session already valid from a previous launch — skip login
        // entirely, satisfying "no forced logout on restart".
        if (Session.isLoggedIn()) {
            goToChat()
            return
        }

        setContentView(R.layout.activity_login)

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
