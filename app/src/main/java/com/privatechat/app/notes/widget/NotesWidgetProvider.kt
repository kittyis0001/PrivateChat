package com.privatechat.app.notes.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.privatechat.app.R
import com.privatechat.app.notes.ui.NotesActivity

/**
 * Home-screen widget: the latest notes (up to 8) with a new-note
 * button. Taps go through [NotesActivity] — never straight to the
 * editor — so the Notes app lock still runs first, and a locked
 * note still asks for the Notes password (see NotesActivity.openEditor).
 *
 * The widget refreshes whenever the user returns to the Notes home
 * screen (NotesActivity.onResume calls [updateAll]) and on every
 * system-driven onUpdate.
 */
class NotesWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { updateAppWidget(context, appWidgetManager, it) }
    }

    companion object {
        /** Reloads the list on every placed widget (no-op when none is placed). */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, NotesWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widgetNoteList)
            ids.forEach { updateAppWidget(context, manager, it) }
        }

        private fun updateAppWidget(
            context: Context,
            manager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_notes)

            // The list itself (a RemoteViewsService fills the rows).
            val serviceIntent = Intent(context, NotesWidgetService::class.java)
            views.setRemoteAdapter(R.id.widgetNoteList, serviceIntent)
            views.setEmptyView(R.id.widgetNoteList, R.id.widgetEmpty)

            // One tap per row: the row's fill-in intent carries the note id.
            val template = Intent(context, NotesActivity::class.java)
            val templatePi = PendingIntent.getActivity(
                context, 0, template,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setPendingIntentTemplate(R.id.widgetNoteList, templatePi)

            // The "+" button opens a blank editor (note id 0 = new note).
            val newNote = Intent(context, NotesActivity::class.java)
                .putExtra(NotesActivity.EXTRA_OPEN_NOTE_ID, 0L)
            val newNotePi = PendingIntent.getActivity(
                context, 1, newNote,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetNewNote, newNotePi)

            manager.updateAppWidget(appWidgetId, views)
        }
    }
}
