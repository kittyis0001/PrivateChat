package com.privatechat.app.notes.security

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Notes-only app lock. Stores a PBKDF2 (HmacSHA1, API-24-safe) password hash with a random
 * salt in a dedicated SharedPreferences file — never plaintext, and
 * completely separate from the chat login password and the promo
 * access-code gate.
 */
object NotesLockManager {

    private const val PREFS = "kitty_notes_prefs"
    private const val KEY_ENABLED = "lock_enabled"
    private const val KEY_HASH = "lock_hash"
    private const val KEY_SALT = "lock_salt"
    private const val KEY_ACCENT = "theme_accent"
    private const val ITERATIONS = 120_000
    private const val KEY_LENGTH = 256

    /** Process-local: relaunching the app requires unlocking again. */
    @Volatile
    var unlockedThisProcess: Boolean = false

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isLockEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setPassword(context: Context, password: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = hash(password, salt)
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, true)
            .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .apply()
        unlockedThisProcess = true
    }

    fun disableLock(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, false)
            .remove(KEY_HASH)
            .remove(KEY_SALT)
            .apply()
        unlockedThisProcess = false
    }

    fun verify(context: Context, password: String): Boolean {
        val p = prefs(context)
        val hashB64 = p.getString(KEY_HASH, null) ?: return false
        val saltB64 = p.getString(KEY_SALT, null) ?: return false
        val expected = Base64.decode(hashB64, Base64.NO_WRAP)
        val actual = hash(password, Base64.decode(saltB64, Base64.NO_WRAP))
        val ok = expected.contentEquals(actual)
        if (ok) unlockedThisProcess = true
        return ok
    }

    private fun hash(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
    }

    // ---- Notes dark mode (notes-scoped, persisted) ----
    // Stored in the same dedicated Notes preferences file. Applied by
    // NotesActivity before its first frame so reopening the app keeps
    // the chosen mode. The chat app's own theme preference (Session)
    // is a separate source of truth and is never written here.
    private const val KEY_NOTES_DARK_SET = "notes_dark_set"
    private const val KEY_NOTES_DARK = "notes_dark"

    /** true = dark, false = light, null = user has not chosen in Notes yet. */
    fun notesDarkMode(context: Context): Boolean? {
        val p = prefs(context)
        return if (p.contains(KEY_NOTES_DARK_SET)) p.getBoolean(KEY_NOTES_DARK, false) else null
    }

    fun setNotesDarkMode(context: Context, dark: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_NOTES_DARK_SET, true)
            .putBoolean(KEY_NOTES_DARK, dark)
            .apply()
    }

    // ---- Notes sort order (notes-scoped, persisted) ----
    // 0 = pinned + last edited (original order), 1 = newest first,
    // 2 = oldest first, 3 = title A-Z. Pinned notes stay on top in
    // every mode, exactly as the original list behaved.
    private const val KEY_SORT_MODE = "notes_sort_mode"

    fun getSortMode(context: Context): Int = prefs(context).getInt(KEY_SORT_MODE, 0)

    fun setSortMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_SORT_MODE, mode).apply()
    }

    // ---- Notes theme accent (notes-scoped only) ----
    fun getAccent(context: Context): String = prefs(context).getString(KEY_ACCENT, "pink") ?: "pink"
    fun setAccent(context: Context, accent: String) {
        prefs(context).edit().putString(KEY_ACCENT, accent).apply()
    }

    // ---- Google Drive backup bookkeeping (notes-scoped) ----
    private const val KEY_DRIVE_LAST_BACKUP = "drive_last_backup"

    fun getDriveLastBackup(context: Context): Long = prefs(context).getLong(KEY_DRIVE_LAST_BACKUP, 0L)

    fun setDriveLastBackup(context: Context, timeMillis: Long) {
        prefs(context).edit().putLong(KEY_DRIVE_LAST_BACKUP, timeMillis).apply()
    }
}
