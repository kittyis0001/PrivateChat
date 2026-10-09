package com.privatechat.app.data

/**
 * Process-local gate for the Secret Chat access screen.
 *
 * This is an extra UI gate shown before the existing username/password
 * login. It is intentionally kept only in process memory — never in
 * SharedPreferences, DataStore or any other persistent store — so a
 * fresh app process always starts locked and requires the code again.
 * Activity recreation (rotation, theme change) runs in the same process
 * and therefore does not force re-entry, which matches the expected
 * Android configuration-change behaviour.
 *
 * This gate does not replace authentication. The existing login check
 * (Session + Firebase Realtime Database password verification in
 * LoginActivity) remains the real authentication step.
 */
object AccessGate {

    /** The required access code. */
    const val REQUIRED_CODE = "67"

    @Volatile
    var isUnlocked: Boolean = false
        private set

    fun unlock() {
        isUnlocked = true
    }

    fun lock() {
        isUnlocked = false
    }
}
