package com.privatechat.app.notes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.privatechat.app.R
import com.privatechat.app.notes.backup.NotesBackup
import com.privatechat.app.notes.data.NotesRepository
import com.privatechat.app.notes.drive.DriveBackupManager
import com.privatechat.app.notes.security.NotesLockManager
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Google Drive backup for Kitty Notes (Drive appDataFolder — a
 * hidden per-app folder; the same JSON format as the local export).
 *
 * Honest states: until the app owner completes the one-time Google
 * Cloud registration (package name + this build's SHA-1, shown on
 * this screen), Connect reports "setup incomplete" instead of
 * pretending anything was backed up. Chat data is never involved.
 */
@Suppress("DEPRECATION")
class DriveBackupActivity : AppCompatActivity() {

    private val repo by lazy { NotesRepository(this) }

    private lateinit var statusText: TextView
    private lateinit var accountText: TextView
    private lateinit var lastBackupText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var connectBtn: View
    private lateinit var backupBtn: View
    private lateinit var restoreBtn: View
    private lateinit var signOutBtn: View

    /** Operation to retry after Google's one-time consent screen. */
    private var pendingRetry: (() -> Unit)? = null

    private val signInOptions: GoogleSignInOptions by lazy {
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveBackupManager.SCOPE_URI))
            .build()
    }

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            task.getResult(ApiException::class.java)
            refreshUi()
        } catch (e: ApiException) {
            when (e.statusCode) {
                CommonStatusCodes.DEVELOPER_ERROR -> showSetupIncomplete()
                // 12501: Google sign-in cancelled by the user
                12501 -> Unit
                else -> toast(getString(R.string.notes_drive_failed))
            }
            refreshUi()
        }
    }

    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val retry = pendingRetry
        pendingRetry = null
        retry?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_drive_backup)

        statusText = findViewById(R.id.driveStatus)
        accountText = findViewById(R.id.driveAccount)
        lastBackupText = findViewById(R.id.driveLastBackup)
        progress = findViewById(R.id.driveProgress)
        connectBtn = findViewById(R.id.driveConnectBtn)
        backupBtn = findViewById(R.id.driveBackupBtn)
        restoreBtn = findViewById(R.id.driveRestoreBtn)
        signOutBtn = findViewById(R.id.driveSignOutBtn)

        findViewById<View>(R.id.driveBack).setOnClickListener { finish() }
        connectBtn.setOnClickListener {
            signInLauncher.launch(GoogleSignIn.getClient(this, signInOptions).signInIntent)
        }
        signOutBtn.setOnClickListener {
            setBusy(true)
            GoogleSignIn.getClient(this, signInOptions).signOut()
                .addOnCompleteListener { setBusy(false); refreshUi() }
        }
        backupBtn.setOnClickListener { doBackup() }
        restoreBtn.setOnClickListener { confirmRestore() }

        findViewById<TextView>(R.id.drivePackageValue).text = packageName
        findViewById<TextView>(R.id.driveShaValue).text = signingSha1()
        findViewById<View>(R.id.driveCopyShaBtn).setOnClickListener {
            val cm = getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("sha1", signingSha1()))
            toast(getString(R.string.notes_drive_copied))
        }
        refreshUi()
    }

    private fun refreshUi() {
        val account = DriveBackupManager.signedInAccount(this)
        if (account == null) {
            statusText.text = getString(R.string.notes_drive_not_connected)
            accountText.text = ""
            connectBtn.visibility = View.VISIBLE
            backupBtn.visibility = View.GONE
            restoreBtn.visibility = View.GONE
            signOutBtn.visibility = View.GONE
        } else {
            statusText.text = getString(R.string.notes_drive_connected)
            accountText.text = account.email.orEmpty()
            connectBtn.visibility = View.GONE
            backupBtn.visibility = View.VISIBLE
            restoreBtn.visibility = View.VISIBLE
            signOutBtn.visibility = View.VISIBLE
        }
        val last = NotesLockManager.getDriveLastBackup(this)
        lastBackupText.text = if (last > 0) {
            getString(R.string.notes_drive_last_backup, DATE_FMT.format(Date(last)))
        } else {
            getString(R.string.notes_drive_never)
        }
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        connectBtn.isEnabled = !busy
        backupBtn.isEnabled = !busy
        restoreBtn.isEnabled = !busy
        signOutBtn.isEnabled = !busy
    }

    /** Runs [action] with a Drive access token, handling one-time consent. */
    private fun runWithToken(action: suspend (String) -> Unit) {
        val account = DriveBackupManager.signedInAccount(this)
        if (account == null) {
            toast(getString(R.string.notes_drive_connect_first))
            return
        }
        lifecycleScope.launch {
            setBusy(true)
            try {
                val token = DriveBackupManager.accessToken(this@DriveBackupActivity, account)
                action(token)
            } catch (e: DriveBackupManager.NeedsConsent) {
                pendingRetry = { runWithToken(action) }
                runCatching { consentLauncher.launch(e.intent) }
                    .onFailure { toast(getString(R.string.notes_drive_failed)) }
            } catch (e: Exception) {
                toast(getString(R.string.notes_drive_failed))
            } finally {
                setBusy(false)
                refreshUi()
            }
        }
    }

    private fun doBackup() {
        runWithToken { token ->
            val json = NotesBackup.exportJson(
                repo.getAllForBackup(),
                repo.getCategoriesOnce(),
                repo.getStickiesOnce()
            )
            DriveBackupManager.upload(token, json)
            NotesLockManager.setDriveLastBackup(this@DriveBackupActivity, System.currentTimeMillis())
            toast(getString(R.string.notes_drive_backup_done))
        }
    }

    private fun confirmRestore() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.notes_drive_restore_title))
            .setMessage(getString(R.string.notes_drive_restore_msg))
            .setPositiveButton(getString(R.string.notes_restore)) { _, _ -> doRestore() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun doRestore() {
        runWithToken { token ->
            val meta = DriveBackupManager.findBackup(token)
            if (meta == null) {
                toast(getString(R.string.notes_drive_no_backup))
                return@runWithToken
            }
            val parsed = NotesBackup.parseJson(DriveBackupManager.download(token, meta.id))
            // Same import semantics as the local file restore: match
            // categories by name, keep existing notes, add new ones.
            val oldIdToName = parsed.categories.associate { it.id to it.name }
            val nameToId = repo.getCategoriesOnce().associate { it.name to it.id }.toMutableMap()
            var imported = 0
            parsed.notes.forEach { n ->
                val name = n.categoryId?.let { oldIdToName[it] }
                val mappedId: Long? = when {
                    name == null -> null
                    nameToId.containsKey(name) -> nameToId[name]
                    else -> {
                        val newId = repo.addCategory(name)
                        nameToId[name] = newId
                        newId
                    }
                }
                repo.importNote(n.copy(categoryId = mappedId))
                imported++
            }
            parsed.stickies.forEach { repo.importSticky(it) }
            // Reminders inside restored notes re-arm on next Notes launch.
            toast(getString(R.string.notes_drive_restored, imported))
        }
    }

    private fun showSetupIncomplete() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.notes_drive_setup_incomplete_title))
            .setMessage(getString(R.string.notes_drive_setup_incomplete_msg))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** SHA-1 of the certificate signing THIS installed build. */
    private fun signingSha1(): String {
        return runCatching {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
            }
            val cert = signatures?.firstOrNull()?.toByteArray() ?: return "-"
            MessageDigest.getInstance("SHA-1").digest(cert)
                .joinToString(":") { "%02X".format(it) }
        }.getOrDefault("-")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private val DATE_FMT = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault())
    }
}
