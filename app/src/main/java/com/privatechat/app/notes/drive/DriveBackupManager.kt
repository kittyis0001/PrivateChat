package com.privatechat.app.notes.drive

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Google Drive backup for Kitty Notes, using the Drive
 * appDataFolder — a hidden per-app folder in the user's own Google
 * Drive. Only the Notes JSON backup (same format as the local file
 * export) is uploaded. Chat data/messages are never read or sent.
 *
 * This only works once the app owner has registered the app in
 * their own Google Cloud project (package name + signing SHA-1);
 * until then sign-in fails and the UI says setup is incomplete —
 * nothing is ever faked as "backed up" here.
 */
object DriveBackupManager {

    const val FILE_NAME = "kitty-notes-backup.json"
    const val SCOPE_URI = "https://www.googleapis.com/auth/drive.appdata"
    private const val API = "https://www.googleapis.com/drive/v3"
    private const val UPLOAD_API = "https://www.googleapis.com/upload/drive/v3"

    /** Drive needs a one-time user consent; [intent] opens it. */
    class NeedsConsent(val intent: Intent) : Exception("Drive consent required")

    fun signedInAccount(context: Context): GoogleSignInAccount? =
        GoogleSignIn.getLastSignedInAccount(context.applicationContext)

    fun signedInEmail(context: Context): String? = signedInAccount(context)?.email

    /**
     * OAuth access token for the Drive appData scope. Blocking —
     * call from IO. Throws [NeedsConsent] when Google wants the
     * user to approve access in a browser/app screen first.
     */
    suspend fun accessToken(context: Context, account: GoogleSignInAccount): String =
        withContext(Dispatchers.IO) {
            val acct = account.account ?: throw IOException("Google account unavailable")
            try {
                GoogleAuthUtil.getToken(context.applicationContext, acct, "oauth2:$SCOPE_URI")
            } catch (e: UserRecoverableAuthException) {
                throw NeedsConsent(e.intent ?: throw IOException("Consent unavailable"))
            }
        }

    data class DriveFile(val id: String, val modifiedTime: String?)

    /** Finds the newest Notes backup in appDataFolder, if any. */
    suspend fun findBackup(token: String): DriveFile? = withContext(Dispatchers.IO) {
        val query = URLEncoder.encode("name = '$FILE_NAME'", "UTF-8")
        val url = URL("$API/files?spaces=appDataFolder&q=$query&fields=files(id,name,modifiedTime)&orderBy=modifiedTime%20desc&pageSize=5")
        val json = request("GET", url, token, null, null)
        val files = json.optJSONArray("files") ?: return@withContext null
        if (files.length() == 0) return@withContext null
        val first = files.getJSONObject(0)
        DriveFile(first.getString("id"), first.optString("modifiedTime").ifBlank { null })
    }

    /** Uploads (creates or overwrites) the backup file. Returns its Drive id. */
    suspend fun upload(token: String, json: String): String = withContext(Dispatchers.IO) {
        val existing = findBackup(token)
        val body = json.toByteArray(Charsets.UTF_8)
        if (existing != null) {
            requestRaw("PATCH", URL("$UPLOAD_API/files/${existing.id}?uploadType=media"), token, "application/json", body)
            existing.id
        } else {
            val boundary = "kitty_${System.currentTimeMillis()}"
            val meta = JSONObject()
                .put("name", FILE_NAME)
                .put("parents", org.json.JSONArray().put("appDataFolder"))
                .toString()
            val payload = buildString {
                append("--$boundary\r\n")
                append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
                append(meta).append("\r\n")
                append("--$boundary\r\n")
                append("Content-Type: application/json\r\n\r\n")
                append(json).append("\r\n")
                append("--$boundary--")
            }
            val created = request(
                "POST",
                URL("$UPLOAD_API/files?uploadType=multipart"),
                token,
                "multipart/related; boundary=$boundary",
                payload.toByteArray(Charsets.UTF_8)
            )
            created.getString("id")
        }
    }

    /** Downloads the backup file's JSON text. */
    suspend fun download(token: String, fileId: String): String = withContext(Dispatchers.IO) {
        val conn = open(URL("$API/files/$fileId?alt=media"), "GET", token, null)
        val code = conn.responseCode
        if (code !in 200..299) throw IOException("Drive download failed (HTTP $code)")
        conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    // ---- small REST helpers ----

    private fun open(url: URL, method: String, token: String, contentType: String?): HttpURLConnection {
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.setRequestProperty("Authorization", "Bearer $token")
        if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        return conn
    }

    private fun requestRaw(method: String, url: URL, token: String, contentType: String, body: ByteArray?): String {
        val conn = open(url, method, token, contentType)
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(body) }
        }
        val code = conn.responseCode
        val text = runCatching {
            (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        }.getOrNull().orEmpty()
        if (code !in 200..299) throw IOException("Drive request failed (HTTP $code)")
        return text
    }

    private fun request(method: String, url: URL, token: String, contentType: String?, body: ByteArray?): JSONObject {
        val text = requestRaw(method, url, token, contentType ?: "application/json", body)
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }
}
