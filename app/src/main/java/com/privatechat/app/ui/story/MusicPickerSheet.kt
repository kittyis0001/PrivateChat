package com.privatechat.app.ui.story

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.privatechat.app.data.model.Song
import com.privatechat.app.data.repository.MusicRepository
import kotlinx.coroutines.launch

/**
 * Same-to-same UX as the reference's #musicPicker bottom sheet: a
 * search box, For You / Trending / Saved tabs, and a scrollable list
 * of song cards. Tapping a card's thumbnail previews it in place
 * (toggle play/pause); tapping the "+" selects it for the story and
 * closes the sheet; "🔖" saves it for later.
 */
class MusicPickerSheet(
    private val activity: AppCompatActivity,
    currentUser: String,
    private val previewContainer: ViewGroup,
    private val getCaption: () -> String,
    private val onSongSelected: (Song) -> Unit
) {
    private val repository = MusicRepository(currentUser)
    private val dialog = BottomSheetDialog(activity)
    private val handler = Handler(Looper.getMainLooper())
    private var searchDebounce: Runnable? = null
    private var activeTab = "foryou"
    private var previewingId: String? = null

    private lateinit var listContainer: LinearLayout
    private lateinit var loadingView: ProgressBar
    private lateinit var emptyView: TextView
    private lateinit var vibeBadge: TextView
    private val tabButtons = mutableMapOf<String, TextView>()

    fun show() {
        val root = buildRoot()
        dialog.setContentView(root)
        dialog.setOnDismissListener { StoryMusicPlayer.stop(previewContainer) }
        dialog.show()
        loadTab("foryou")
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    private fun buildRoot(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(20))
        }

        root.addView(TextView(activity).apply {
            text = "🎵 Add Music"
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(resources.textColor())
        })

        val searchRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            }
        }
        val searchInput = EditText(activity).apply {
            hint = "Search songs, artist, mood…"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setBackgroundResource(com.privatechat.app.R.drawable.bg_input_field)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        searchInput.onDebouncedTextChanged { text ->
            searchDebounce?.let { handler.removeCallbacks(it) }
            val query = text.trim()
            if (query.length >= 2) {
                val runnable = Runnable { loadTab("search", query) }
                searchDebounce = runnable
                handler.postDelayed(runnable, 600)
            }
        }
        searchRow.addView(searchInput)
        root.addView(searchRow)

        val tabRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            }
        }
        listOf("foryou" to "For You", "trending" to "Trending", "saved" to "Saved").forEach { (id, label) ->
            val tab = TextView(activity).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { selectTab(id) }
            }
            tabButtons[id] = tab
            tabRow.addView(tab)
        }
        root.addView(tabRow)
        applyTabStyles()

        vibeBadge = TextView(activity).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#9B6EF3"))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        }
        root.addView(vibeBadge)

        loadingView = ProgressBar(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
                topMargin = dp(30)
            }
            visibility = View.GONE
        }
        root.addView(loadingView)

        emptyView = TextView(activity).apply {
            text = "No songs found"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#808080"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(30)
            }
            visibility = View.GONE
        }
        root.addView(emptyView)

        val scroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(360))
        }
        listContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listContainer)
        root.addView(scroll)

        return root
    }

    private fun applyTabStyles() {
        tabButtons.forEach { (id, view) ->
            val active = id == activeTab
            view.setTextColor(if (active) Color.WHITE else Color.parseColor("#B0B0B0"))
            view.setBackgroundResource(if (active) com.privatechat.app.R.drawable.bg_story_share_button else 0)
        }
    }

    private fun selectTab(id: String) {
        activeTab = id
        applyTabStyles()
        StoryMusicPlayer.stop(previewContainer)
        previewingId = null
        loadTab(id)
    }

    private fun loadTab(tab: String, query: String = "") {
        listContainer.removeAllViews()
        vibeBadge.visibility = View.GONE
        emptyView.visibility = View.GONE
        loadingView.visibility = View.VISIBLE

        activity.lifecycleScope.launch {
            try {
                val songs: List<Song>
                when (tab) {
                    "search" -> songs = repository.search(query)
                    "trending" -> songs = repository.trending()
                    "saved" -> {
                        songs = suspendFetchSaved()
                    }
                    else -> {
                        val (result, mood, vibe) = repository.recommend(getCaption())
                        songs = result
                        if (mood != null) {
                            vibeBadge.text = "✨ $mood · ${vibe.orEmpty()}"
                            vibeBadge.visibility = View.VISIBLE
                        }
                    }
                }
                loadingView.visibility = View.GONE
                if (songs.isEmpty()) {
                    emptyView.visibility = View.VISIBLE
                } else {
                    songs.forEach { listContainer.addView(buildSongCard(it, tab)) }
                }
            } catch (e: Exception) {
                loadingView.visibility = View.GONE
                emptyView.text = "Failed to load songs"
                emptyView.visibility = View.VISIBLE
            }
        }
    }

    private suspend fun suspendFetchSaved(): List<Song> =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            repository.fetchSaved { cont.resumeWith(Result.success(it)) }
        }

    private fun buildSongCard(song: Song, tab: String): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }

        val thumb = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundResource(com.privatechat.app.R.drawable.bg_media_thumb_placeholder)
        }
        if (song.thumbnail.isNotBlank()) {
            Glide.with(activity).load(song.thumbnail).into(thumb)
        }
        thumb.setOnClickListener { togglePreview(song) }
        row.addView(thumb)

        val infoCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
            }
            setOnClickListener { togglePreview(song) }
        }
        infoCol.addView(TextView(activity).apply {
            text = song.title
            textSize = 13f
            maxLines = 1
            setTextColor(resources.textColor())
        })
        infoCol.addView(TextView(activity).apply {
            text = song.artist
            textSize = 11f
            maxLines = 1
            setTextColor(Color.parseColor("#909090"))
        })
        infoCol.addView(TextView(activity).apply {
            text = if (song.source == "youtube") "▶ YouTube" else "♫ Jamendo"
            textSize = 10f
            setTextColor(Color.parseColor("#B0B0B0"))
        })
        row.addView(infoCol)

        if (tab == "saved") {
            row.addView(iconButton("－") {
                repository.removeSavedSong(song)
                loadTab("saved")
            })
        } else {
            row.addView(iconButton("🔖") { repository.saveSong(song) { } })
        }
        row.addView(iconButton("＋") {
            StoryMusicPlayer.stop(previewContainer)
            onSongSelected(song)
            dialog.dismiss()
        })

        return row
    }

    private fun iconButton(label: String, onClick: () -> Unit): View = TextView(activity).apply {
        text = label
        textSize = 18f
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(6), dp(10), dp(6))
        setOnClickListener { onClick() }
    }

    private fun togglePreview(song: Song) {
        val id = song.id()
        if (previewingId == id) {
            StoryMusicPlayer.stop(previewContainer)
            previewingId = null
        } else {
            StoryMusicPlayer.play(previewContainer, song)
            previewingId = id
        }
    }

    private fun android.content.res.Resources.textColor(): Int =
        getColor(com.privatechat.app.R.color.textPrimary, activity.theme)
}

private fun EditText.onDebouncedTextChanged(afterChanged: (String) -> Unit) {
    addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: android.text.Editable?) {
            afterChanged(s?.toString()?.trim().orEmpty())
        }
    })
}
