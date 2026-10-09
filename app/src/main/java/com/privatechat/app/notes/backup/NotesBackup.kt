package com.privatechat.app.notes.backup

import com.privatechat.app.notes.data.Category
import com.privatechat.app.notes.data.Note
import com.privatechat.app.notes.data.StickyNote
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local backup/restore for Notes data as JSON. Used with the Android
 * Storage Access Framework (user picks the file). There is no Notes
 * cloud backend in this project, so cloud sync is reported as
 * not configured — this never pretends to sync anywhere, and it only
 * ever reads/writes Notes tables.
 */
object NotesBackup {

    fun exportJson(notes: List<Note>, categories: List<Category>, stickies: List<StickyNote>): String {
        val root = JSONObject()
        root.put("app", "Kitty Notes (Private Chat)")
        root.put("version", 1)
        root.put("exportedAt", System.currentTimeMillis())

        val catArr = JSONArray()
        categories.forEach { c ->
            catArr.put(JSONObject().put("name", c.name).put("isDefault", c.isDefault))
        }
        root.put("categories", catArr)

        val noteArr = JSONArray()
        notes.forEach { n ->
            noteArr.put(
                JSONObject()
                    .put("title", n.title)
                    .put("content", n.content)
                    .put("categoryId", n.categoryId ?: JSONObject.NULL)
                    .put("pinned", n.pinned)
                    .put("archived", n.archived)
                    .put("deletedAt", n.deletedAt ?: JSONObject.NULL)
                    .put("createdAt", n.createdAt)
                    .put("updatedAt", n.updatedAt)
            )
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
        val stickies: List<StickyNote>
    )

    /** Parses a backup file. Throws on malformed input so the UI can report it. */
    fun parseJson(text: String): Parsed {
        val root = JSONObject(text)
        val cats = mutableListOf<Category>()
        val catArr = root.optJSONArray("categories") ?: JSONArray()
        for (i in 0 until catArr.length()) {
            val o = catArr.getJSONObject(i)
            cats.add(Category(name = o.optString("name"), isDefault = o.optBoolean("isDefault")))
        }
        val notes = mutableListOf<Note>()
        val noteArr = root.optJSONArray("notes") ?: JSONArray()
        for (i in 0 until noteArr.length()) {
            val o = noteArr.getJSONObject(i)
            notes.add(
                Note(
                    title = o.optString("title"),
                    content = o.optString("content"),
                    categoryId = if (o.isNull("categoryId")) null else o.optLong("categoryId"),
                    pinned = o.optBoolean("pinned"),
                    archived = o.optBoolean("archived"),
                    deletedAt = if (o.isNull("deletedAt")) null else o.optLong("deletedAt"),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
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
        return Parsed(cats, notes, stickies)
    }
}
