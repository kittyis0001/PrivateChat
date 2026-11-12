package com.privatechat.app.notes.sync

import com.google.firebase.database.FirebaseDatabase
import com.privatechat.app.notes.data.CalculatorHistory
import com.privatechat.app.notes.data.NotesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Calculator history sync (Google login only). The history is stored
 * as one JSON blob at calculator_history/<uid>/backup — the same
 * shape as the Notes sync, but a separate node so the Notes backup
 * is untouched. Merge dedupes on (expression, result, createdAt).
 *
 * The Firebase rules need a calculator_history node (same per-uid
 * rule as notes_sync); until it is published the calls fail silently
 * and history simply stays on the phone.
 */
object CalculatorHistorySync {

    private const val DB_URL = "https://private-chat-7a103-default-rtdb.asia-southeast1.firebasedatabase.app"
    private const val ROOT = "calculator_history"

    /** One sync at a time: a download and an upload must not interleave. */
    private val syncMutex = Mutex()

    private fun backupRef(uid: String) =
        FirebaseDatabase.getInstance(DB_URL).getReference(ROOT).child(uid).child("backup")

    /** Uploads the whole local calculator history. False when not signed in or offline. */
    suspend fun uploadNow(repo: NotesRepository): Boolean {
        val uid = NotesSyncManager.currentUid() ?: return false
        return try {
            syncMutex.withLock {
                withContext(Dispatchers.IO) {
                    NotesSyncManager.awaitTask(
                        backupRef(uid).setValue(encode(repo.getCalculatorHistory()))
                    )
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Downloads the cloud history and merges it in. An entry is skipped
     * when the same (expression, result, createdAt) is already here.
     * Returns how many entries were added.
     */
    suspend fun downloadAndMerge(repo: NotesRepository): Int {
        val uid = NotesSyncManager.currentUid() ?: return 0
        val json = try {
            NotesSyncManager.awaitTask(backupRef(uid).get())
                .getValue(String::class.java)
        } catch (_: Exception) {
            null
        } ?: return 0
        val entries = try {
            decode(json)
        } catch (_: Exception) {
            return 0
        }
        return syncMutex.withLock {
            withContext(Dispatchers.IO) {
                val localKeys = repo.getCalculatorHistory()
                    .map { it.expression to it.result to it.createdAt }.toHashSet()
                var added = 0
                entries.forEach { e ->
                    val key = e.expression to e.result to e.createdAt
                    if (key !in localKeys) {
                        repo.importCalculatorHistory(e)
                        added++
                    }
                }
                added
            }
        }
    }

    private fun encode(entries: List<CalculatorHistory>): String {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put("e", e.expression)
                    .put("r", e.result)
                    .put("t", e.createdAt)
            )
        }
        return JSONObject().put("version", 1).put("entries", arr).toString()
    }

    private fun decode(json: String): List<CalculatorHistory> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("entries") ?: JSONArray()
        val out = mutableListOf<CalculatorHistory>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                CalculatorHistory(
                    expression = o.optString("e"),
                    result = o.optString("r"),
                    createdAt = o.optLong("t", System.currentTimeMillis())
                )
            )
        }
        return out
    }
}
