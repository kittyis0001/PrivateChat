package com.privatechat.app.notes.sync

import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import com.privatechat.app.notes.backup.NotesBackup
import com.privatechat.app.notes.data.NotesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Keep-style Notes sync, backed by the app's own Firebase Realtime
 * Database (private-chat-7a103, the same database the chat already
 * uses). No Google Drive anywhere.
 *
 * How it works: signing in with Google (see NotesActivity) gives a
 * Firebase user; the whole local Notes state — the same JSON as the
 * local file export — is stored under `notes_sync/<uid>/backup` and
 * merged back on sign-in and on returning to the home screen. Notes
 * written without signing in stay on this device only: deleting the
 * app deletes them, exactly like Google Keep.
 *
 * Honest limits: note photos are local files and are NOT in the JSON,
 * so they never sync; syncing needs internet; if the same note is
 * edited on two devices at once, the last upload wins.
 */
object NotesSyncManager {

    private const val DB_URL = "https://private-chat-7a103-default-rtdb.asia-southeast1.firebasedatabase.app"
    private const val ROOT = "notes_sync"

    /** The Web client ID goes in R.string.google_web_client_id. */
    const val WEB_CLIENT_ID_PLACEHOLDER = "PASTE_YOUR_WEB_CLIENT_ID_HERE"

    /** One sync at a time: a download and an upload must not interleave. */
    private val syncMutex = Mutex()

    fun isSignedIn(): Boolean = FirebaseAuth.getInstance().currentUser != null

    fun currentEmail(): String? = FirebaseAuth.getInstance().currentUser?.email

    fun currentUid(): String? = FirebaseAuth.getInstance().currentUser?.uid

    private fun backupRef(uid: String) =
        FirebaseDatabase.getInstance(DB_URL).getReference(ROOT).child(uid).child("backup")

    /** Turns a Play-services Task into a suspend call (no extra dependency). */
    suspend fun <T> awaitTask(task: Task<T>): T = suspendCancellableCoroutine { cont ->
        task.addOnSuccessListener { v -> if (cont.isActive) cont.resume(v) }
        task.addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
        task.addOnCanceledListener { if (cont.isActive) cont.cancel() }
    }

    /** Uploads the whole local Notes state. False when not signed in or offline. */
    suspend fun uploadNow(repo: NotesRepository): Boolean {
        val uid = currentUid() ?: return false
        return try {
            syncMutex.withLock {
                withContext(Dispatchers.IO) {
                    val json = NotesBackup.exportJson(
                        repo.getAllForBackup(),
                        repo.getCategoriesOnce(),
                        repo.getStickiesOnce()
                    )
                    awaitTask(backupRef(uid).setValue(json))
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Downloads the cloud backup, or null when there is none / offline. */
    suspend fun downloadJson(): String? {
        val uid = currentUid() ?: return null
        return try {
            val snap: DataSnapshot = awaitTask(backupRef(uid).get())
            snap.getValue(String::class.java)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Merges the cloud backup into the local database. A cloud note is
     * skipped when a local note already has the same createdAt (the same
     * note, possibly edited on this phone). Categories are matched by
     * name, like the local file restore. Returns how many notes were added.
     */
    suspend fun restoreFromCloud(repo: NotesRepository): Int {
        val json = downloadJson() ?: return 0
        val parsed = try {
            NotesBackup.parseJson(json)
        } catch (_: Exception) {
            return 0
        }
        return syncMutex.withLock {
            withContext(Dispatchers.IO) {
                val localCreated = repo.getAllForBackup().map { it.createdAt }.toHashSet()
                val oldIdToName = parsed.categories.associate { it.id to it.name }
                val nameToId = repo.getCategoriesOnce().associate { it.name to it.id }.toMutableMap()
                var imported = 0
                parsed.notes.forEach { n ->
                    if (n.createdAt in localCreated) return@forEach
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
                val localStickyKeys =
                    repo.getStickiesOnce().map { it.text to it.createdAt }.toHashSet()
                parsed.stickies.forEach { s ->
                    if ((s.text to s.createdAt) !in localStickyKeys) repo.importSticky(s)
                }
                imported
            }
        }
    }

    /**
     * Right after sign-in: pull the cloud notes in, then push the merged
     * state back up, so the cloud holds everything. Returns how many
     * notes came down.
     */
    suspend fun syncAfterSignIn(repo: NotesRepository): Int {
        val imported = restoreFromCloud(repo)
        uploadNow(repo)
        return imported
    }
}
