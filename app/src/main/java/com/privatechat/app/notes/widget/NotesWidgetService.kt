package com.privatechat.app.notes.widget

import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.privatechat.app.R
import com.privatechat.app.notes.data.Note
import com.privatechat.app.notes.data.NotesRepository
import com.privatechat.app.notes.ui.NotesActivity
import kotlinx.coroutines.runBlocking

/**
 * List adapter for the home-screen widget. Runs in the widget's
 * binder thread, so the Room read uses runBlocking (the standard
 * RemoteViewsFactory pattern). Shows the latest notes only —
 * trashed and archived notes are hidden, exactly like the home list.
 */
class NotesWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        NotesFactory(applicationContext)
}

private class NotesFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private val repo by lazy { NotesRepository(context.applicationContext) }
    private var notes: List<Note> = emptyList()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        notes = runBlocking {
            repo.getAllForBackup()
                .filter { it.deletedAt == null && !it.archived }
                .take(MAX_NOTES)
        }
    }

    override fun onDestroy() {
        notes = emptyList()
    }

    override fun getCount(): Int = notes.size

    override fun getViewAt(position: Int): RemoteViews? {
        if (position !in notes.indices) return null
        val note = notes[position]
        val views = RemoteViews(context.packageName, R.layout.widget_note_item)
        val title = note.title.ifBlank {
            note.content.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        }.ifBlank { context.getString(R.string.notes_untitled) }.take(TITLE_MAX)
        views.setTextViewText(R.id.widgetItemTitle, title)
        views.setTextViewText(
            R.id.widgetItemDate,
            DateUtils.getRelativeTimeSpanString(
                note.updatedAt,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            ).toString()
        )
        // Fill-in intent: merged into the provider's template intent.
        views.setOnClickFillInIntent(
            R.id.widgetItemRoot,
            Intent().putExtra(NotesActivity.EXTRA_OPEN_NOTE_ID, note.id)
        )
        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long =
        if (position in notes.indices) notes[position].id else position.toLong()

    override fun hasStableIds(): Boolean = true

    private companion object {
        const val MAX_NOTES = 8
        const val TITLE_MAX = 60
    }
}
