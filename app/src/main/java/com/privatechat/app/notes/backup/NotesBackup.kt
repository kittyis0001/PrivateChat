package com.privatechat.app.notes.backup

import android.content.Context
import com.privatechat.app.notes.data.Note
import com.privatechat.app.notes.data.NoteImageStore
import com.privatechat.app.notes.data.NotesRepository
import com.privatechat.app.notes.data.Category
import com.privatechat.app.notes.data.StickyNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Local backup/restore for Notes data as JSON. Used with the Android
 * Storage Access Framework (user picks the file). The same JSON format
 * is also what the Firebase cloud sync uploads (see NotesSyncManager);
 * this file itself only ever reads/writes Notes tables.
 *
 * A backup file is a ZIP: notes.json plus one photos/<file> entry per
 * note photo, so a backup taken out of the app (Downloads) survives an
 * uninstall with the photos. The JSON carries an optional "photos"
 * array per note (file names, keyed by the note's createdAt — the same
 * identity the import/merge already uses). Old plain-JSON backups
 * (no "photos" array, no ZIP) still import exactly as before.
 */
object NotesBackup {

    fun exportJson(
        notes: List<Note>,
        categories: List<Category>,
        stickies: List<StickyNote>,
        photosByCreatedAt: Map<Long, List<String>> = emptyMap()
    ): String {
        val root = JSONObject()
        root.put("app", "Kitty Notes (Private Chat)")
        root.put("version", 1)
        root.put("exportedAt", System.currentTimeMillis())

        val catArr = JSONArray()
        categories.forEach { c ->
            catArr.put(JSONObject().put("id", c.id).put("name", c.name).put("isDefault", c.isDefault))
        }
        root.put("categories", catArr)

        val noteArr = JSONArray()
        notes.forEach { n ->
            val obj = JSONObject()
                .put("title", n.title)
                .put("content", n.content)
                .put("categoryId", n.categoryId ?: JSONObject.NULL)
                .put("pinned", n.pinned)
                .put("archived", n.archived)
                .put("deletedAt", n.deletedAt ?: JSONObject.NULL)
                .put("createdAt", n.createdAt)
                .put("updatedAt", n.updatedAt)
                // v2 fields — additive; older parsers ignore them,
                // and this parser reads them optionally below.
                .put("color", n.color)
                .put("isChecklist", n.isChecklist)
                .put("reminderAt", n.reminderAt ?: JSONObject.NULL)
                .put("reminderRepeat", n.reminderRepeat)
                // v4 field — additive; older parsers ignore it,
                // and this parser reads it optionally below.
                .put("tags", n.tags)
                // v5 fields — additive; older parsers ignore them,
                // and this parser reads them optionally below.
                .put("locked", n.locked)
                .put("expiresAt", n.expiresAt ?: JSONObject.NULL)
                // v6 field — additive; older parsers ignore it,
                // and this parser reads it optionally below.
                .put("countdownAt", n.countdownAt ?: JSONObject.NULL)
            // Photo file names for this note (ZIP backups only; the
            // cloud sync calls exportJson without this map, so its
            // JSON is byte-identical to before).
            val photoNames = photosByCreatedAt[n.createdAt]
            if (!photoNames.isNullOrEmpty()) {
                val photoArr = JSONArray()
                photoNames.forEach { photoArr.put(it) }
                obj.put("photos", photoArr)
            }
            noteArr.put(obj)
        }
        root.put("notes", noteArr)

        val stickyArr = JSONArray()
        stickies.forEach { s ->
            stickyArr.put(
                JSONObject()
                    .put("text", s.text)
                    .put("color", s.color)
                    .put("pinned", s.pinned)
                    .put("createdAt", s.createdAt)
                    .put("updatedAt", s.updatedAt)
            )
        }
        root.put("stickyNotes", stickyArr)
        return root.toString(2)
    }

    data class Parsed(
        val categories: List<Category>,
        val notes: List<Note>,
        val stickies: List<StickyNote>,
        /** Photo file names per note, keyed by the note's createdAt. */
        val photosByCreatedAt: Map<Long, List<String>> = emptyMap()
    )

    /** Parses a backup file. Throws on malformed input so the UI can report it. */
    fun parseJson(text: String): Parsed {
        val root = JSONObject(text)
        val cats = mutableListOf<Category>()
        val catArr = root.optJSONArray("categories") ?: JSONArray()
        for (i in 0 until catArr.length()) {
            val o = catArr.getJSONObject(i)
            cats.add(Category(id = o.optLong("id", 0), name = o.optString("name"), isDefault = o.optBoolean("isDefault")))
        }
        val notes = mutableListOf<Note>()
        val photosByCreatedAt = mutableMapOf<Long, MutableList<String>>()
        val noteArr = root.optJSONArray("notes") ?: JSONArray()
        for (i in 0 until noteArr.length()) {
            val o = noteArr.getJSONObject(i)
            val createdAt = o.optLong("createdAt", System.currentTimeMillis())
            o.optJSONArray("photos")?.let { photoArr ->
                val names = mutableListOf<String>()
                for (j in 0 until photoArr.length()) names.add(photoArr.optString(j))
                if (names.isNotEmpty()) photosByCreatedAt[createdAt] = names
            }
            notes.add(
                Note(
                    title = o.optString("title"),
                    content = o.optString("content"),
                    categoryId = if (o.isNull("categoryId")) null else o.optLong("categoryId"),
                    pinned = o.optBoolean("pinned"),
                    archived = o.optBoolean("archived"),
                    deletedAt = if (o.isNull("deletedAt")) null else o.optLong("deletedAt"),
                    createdAt = createdAt,
                    updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                    color = o.optString("color", ""),
                    isChecklist = o.optBoolean("isChecklist", false),
                    reminderAt = if (o.isNull("reminderAt")) null else o.optLong("reminderAt"),
                    reminderRepeat = o.optString("reminderRepeat", "NONE"),
                    tags = o.optString("tags", ""),
                    locked = o.optBoolean("locked", false),
                    expiresAt = if (o.isNull("expiresAt")) null else o.optLong("expiresAt"),
                    countdownAt = if (o.isNull("countdownAt")) null else o.optLong("countdownAt")
                )
            )
        }
        val stickies = mutableListOf<StickyNote>()
        val stickyArr = root.optJSONArray("stickyNotes") ?: JSONArray()
        for (i in 0 until stickyArr.length()) {
            val o = stickyArr.getJSONObject(i)
            stickies.add(
                StickyNote(
                    text = o.optString("text"),
                    color = o.optString("color", "pink"),
                    pinned = o.optBoolean("pinned"),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
                )
            )
        }
        return Parsed(cats, notes, stickies, photosByCreatedAt)
    }

    // ---------------- ZIP backup (notes.json + photos) ----------------

    /** True when the bytes start with the ZIP magic (PK\x03\x04). */
    fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

    /**
     * Builds one backup ZIP: notes.json plus one photos/<file> entry
     * per photo. [photosByCreatedAt] maps a note's createdAt to its
     * photo file names with their bytes; a photo whose file is gone
     * from disk is simply left out (the note still restores).
     */
    fun zipBytes(
        notes: List<Note>,
        categories: List<Category>,
        stickies: List<StickyNote>,
        photosByCreatedAt: Map<Long, List<Pair<String, ByteArray>>>
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val json = exportJson(
                notes, categories, stickies,
                photosByCreatedAt.mapValues { (_, files) -> files.map { (name, _) -> name } }
            )
            zip.putNextEntry(ZipEntry(NOTES_JSON))
            zip.write(json.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            photosByCreatedAt.values.flatten().forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(PHOTOS_DIR + name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    data class ParsedZip(
        val parsed: Parsed,
        /** Photo file name -> JPEG bytes, for the photos/ entries. */
        val photoBytes: Map<String, ByteArray>
    )

    /** Parses a backup ZIP. Throws on malformed input so the UI can report it. */
    fun parseZip(bytes: ByteArray): ParsedZip {
        var json: String? = null
        val photos = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when {
                    entry.name == NOTES_JSON ->
                        json = zip.readBytes().toString(Charsets.UTF_8)
                    entry.name.startsWith(PHOTOS_DIR) ->
                        photos[entry.name.removePrefix(PHOTOS_DIR)] = zip.readBytes()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val parsed = parseJson(json ?: error("no notes.json in backup"))
        return ParsedZip(parsed, photos)
    }

    /**
     * Reads every photo of the given notes from app-private storage.
     * Runs the file reads on Dispatchers.IO; a missing or unreadable
     * file is skipped, never fatal.
     */
    suspend fun collectPhotos(
        context: Context,
        repo: NotesRepository,
        notes: List<Note>
    ): Map<Long, List<Pair<String, ByteArray>>> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<Long, MutableList<Pair<String, ByteArray>>>()
        notes.forEach { note ->
            repo.getImages(note.id).forEach { image ->
                val file = NoteImageStore.file(context, image.fileName)
                if (!file.exists()) return@forEach
                val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@forEach
                result.getOrPut(note.createdAt) { mutableListOf() }.add(image.fileName to bytes)
            }
        }
        result
    }

    private const val NOTES_JSON = "notes.json"
    private const val PHOTOS_DIR = "photos/"
}
