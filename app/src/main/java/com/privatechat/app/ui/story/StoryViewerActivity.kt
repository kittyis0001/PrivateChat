package com.privatechat.app.ui.story

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.privatechat.app.data.Nicknames
import com.privatechat.app.data.Session
import com.privatechat.app.data.model.StoryGroup
import com.privatechat.app.data.repository.StoryRepository
import com.privatechat.app.databinding.ActivityStoryViewerBinding
import com.privatechat.app.utils.NotificationAvatarFactory
import kotlin.math.abs

/**
 * Same-to-same viewer as the reference web app's #storyViewer:
 * per-story progress bars, tap left/right to navigate, hold to pause,
 * swipe down to close, caption overlay, delete for your own stories.
 * Navigates a fixed snapshot fetched once on open (see
 * StoryRepository.fetchStoriesOnce) rather than a live list, so a new
 * story posted mid-view can't reshuffle what's being paged through.
 */
class StoryViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStoryViewerBinding
    private lateinit var currentUser: String
    private lateinit var repository: StoryRepository

    private var groups: List<StoryGroup> = emptyList()
    private var groupIndex = 0
    private var storyIndex = 0
    private var segments: List<StoryProgressSegment> = emptyList()
    private var isPaused = false
    private var holdRunnable: Runnable? = null
    private var touchStartX = 0f
    private var touchStartY = 0f
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStoryViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val user = Session.currentUser()
        val startUserId = intent.getStringExtra(EXTRA_START_USER_ID)
        if (user == null || startUserId == null) {
            finish()
            return
        }
        currentUser = user
        repository = StoryRepository(currentUser)

        binding.storyCloseBtn.setOnClickListener { finish() }
        binding.storyDeleteBtn.setOnClickListener { confirmDelete() }
        binding.storyMediaWrap.setOnTouchListener { _, event -> handleTouch(event) }

        repository.fetchStoriesOnce { fetchedGroups ->
            if (fetchedGroups.isEmpty()) {
                finish()
                return@fetchStoriesOnce
            }
            groups = fetchedGroups
            val startIndex = groups.indexOfFirst { it.userId == startUserId }
            openGroup(if (startIndex >= 0) startIndex else 0)
        }
    }

    // ── Group / story navigation ─────────────────────────────

    private fun openGroup(index: Int) {
        if (index !in groups.indices) {
            finish()
            return
        }
        groupIndex = index
        storyIndex = 0
        buildProgressSegments(groups[index].stories.size)
        playCurrentStory()
    }

    private fun buildProgressSegments(count: Int) {
        binding.storyProgressBars.removeAllViews()
        segments = (0 until count).map {
            val segment = StoryProgressSegment(this)
            binding.storyProgressBars.addView(segment)
            segment
        }
    }

    private fun playCurrentStory() {
        val group = groups.getOrNull(groupIndex) ?: return finish()
        val story = group.stories.getOrNull(storyIndex) ?: return finish()

        segments.forEachIndexed { i, segment ->
            when {
                i < storyIndex -> segment.complete()
                i == storyIndex -> segment.reset()
                else -> segment.reset()
            }
        }

        // Header
        loadAvatar(group.userId)
        binding.storyViewerName.text = Nicknames.defaultFor(group.userId)
        binding.storyViewerTime.text = timeAgo(story.createdAt)
        binding.storyDeleteBtn.visibility = if (story.userId == currentUser) View.VISIBLE else View.GONE

        if (!story.caption.isNullOrBlank()) {
            binding.storyCapOverlay.visibility = View.VISIBLE
            binding.storyCapOverlay.text = story.caption
        } else {
            binding.storyCapOverlay.visibility = View.GONE
        }

        repository.markViewed(story.key)
        com.privatechat.app.data.StoryViewTracker.markViewed(story.key)

        binding.storyVid.stopPlayback()
        binding.storyViewerOverlayContainer.removeAllViews()
        binding.storyViewerDrawView.clear()
        binding.storyFilterScrim.visibility = View.GONE

        if (story.type == "video") {
            binding.storyImg.visibility = View.GONE
            binding.storyVid.visibility = View.VISIBLE
            binding.storyVid.setVideoURI(android.net.Uri.parse(story.mediaUrl))
            binding.storyVid.setOnPreparedListener { mp ->
                mp.isLooping = false
                mp.start()
                segments[storyIndex].start(mp.duration.toLong().coerceAtLeast(1000L)) { advance() }
            }
            binding.storyVid.setOnCompletionListener { advance() }
            binding.storyVid.setOnErrorListener { _, _, _ -> advance(); true }

            // Video edits (filter/adjust/draw/text/stickers) are stored
            // as overlay data rather than baked in — see
            // StoryUploadActivity's own comment on why. The draw/text/
            // sticker overlays render pixel-exact; the filter/adjust
            // look is a best-effort color-scrim approximation, since a
            // VideoView's raw Surface has no color-matrix hook the way
            // a plain ImageView does.
            story.edit?.let { edit ->
                if (edit.drawStrokes.isNotEmpty()) binding.storyViewerDrawView.loadStrokes(edit.drawStrokes)
                if (edit.texts.isNotEmpty() || edit.stickers.isNotEmpty()) {
                    binding.storyViewerOverlayContainer.loadFromEdit(edit.texts, edit.stickers)
                }
                applyFilterScrim(edit)
            }
        } else {
            binding.storyVid.visibility = View.GONE
            binding.storyImg.visibility = View.VISIBLE
            Glide.with(this).load(story.mediaUrl).into(binding.storyImg)
            segments[storyIndex].start(IMAGE_DURATION_MS) { advance() }
        }
    }

    /** Best-effort approximation of a video story's saved filter/adjust
     * — a flat color scrim over the video, not a pixel-accurate
     * color-matrix (see the call site's comment for why that isn't
     * possible on a raw VideoView surface). */
    private fun applyFilterScrim(edit: com.privatechat.app.data.model.StoryEdit) {
        val filter = StoryFilters.byId(edit.filter)
        val brightnessDelta = filter.brightness * (edit.brightness / 100f) - 1f
        if (brightnessDelta < -0.05f) {
            val alpha = ((-brightnessDelta) * 150).toInt().coerceIn(0, 130)
            binding.storyFilterScrim.setBackgroundColor(android.graphics.Color.argb(alpha, 0, 0, 0))
            binding.storyFilterScrim.visibility = View.VISIBLE
            return
        }
        if (brightnessDelta > 0.05f) {
            val alpha = (brightnessDelta * 150).toInt().coerceIn(0, 100)
            binding.storyFilterScrim.setBackgroundColor(android.graphics.Color.argb(alpha, 255, 255, 255))
            binding.storyFilterScrim.visibility = View.VISIBLE
            return
        }
        val tint: Int? = when {
            filter.grayscale > 0.5f -> android.graphics.Color.argb(46, 128, 128, 128)
            filter.sepia > 0.15f -> android.graphics.Color.argb(36, 139, 90, 43)
            filter.hueRotateDeg < -5f -> android.graphics.Color.argb(20, 90, 200, 250)
            filter.saturation > 1.2f -> android.graphics.Color.argb(15, 255, 149, 0)
            else -> null
        }
        if (tint != null) {
            binding.storyFilterScrim.setBackgroundColor(tint)
            binding.storyFilterScrim.visibility = View.VISIBLE
        }
    }

    private fun advance() {
        if (isPaused) return
        if (storyIndex < groups[groupIndex].stories.size - 1) {
            storyIndex++
            playCurrentStory()
        } else {
            openGroup(groupIndex + 1)
        }
    }

    private fun goBack() {
        if (storyIndex > 0) {
            storyIndex--
            playCurrentStory()
        } else if (groupIndex > 0) {
            groupIndex--
            buildProgressSegments(groups[groupIndex].stories.size)
            storyIndex = groups[groupIndex].stories.size - 1
            playCurrentStory()
        } else {
            // Already at the very first story — restart it, same as
            // the reference's prevStory() behavior at the boundary.
            playCurrentStory()
        }
    }

    // ── Touch handling: tap left/right, hold to pause, swipe down to close ──

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
                val runnable = Runnable { pause() }
                holdRunnable = runnable
                handler.postDelayed(runnable, 150)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.y - touchStartY > 20) {
                    holdRunnable?.let { handler.removeCallbacks(it) }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasPausedByHold = isPaused
                holdRunnable?.let { handler.removeCallbacks(it) }
                holdRunnable = null

                val dy = event.y - touchStartY
                val dx = event.x - touchStartX
                if (dy > 80 && abs(dx) < 60) {
                    finish()
                    return true
                }

                if (wasPausedByHold) {
                    resume()
                } else {
                    // A quick tap (hold-timer never fired) — navigate
                    // based on which third of the screen was tapped.
                    if (event.x < binding.storyMediaWrap.width * 0.35f) goBack() else advance()
                }
            }
        }
        return true
    }

    private fun pause() {
        isPaused = true
        segments.getOrNull(storyIndex)?.pause()
        if (binding.storyVid.visibility == View.VISIBLE && binding.storyVid.isPlaying) {
            binding.storyVid.pause()
        }
    }

    private fun resume() {
        isPaused = false
        segments.getOrNull(storyIndex)?.resume()
        if (binding.storyVid.visibility == View.VISIBLE && !binding.storyVid.isPlaying) {
            binding.storyVid.start()
        }
    }

    // ── Delete (own stories only) ─────────────────────────────

    private fun confirmDelete() {
        val story = groups.getOrNull(groupIndex)?.stories?.getOrNull(storyIndex) ?: return
        pause()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Delete story?")
            .setMessage("This story will be removed for everyone.")
            .setPositiveButton("Delete") { _, _ ->
                repository.deleteStory(story.key) { success ->
                    if (success) {
                        Toast.makeText(this, "Story deleted", Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        Toast.makeText(this, "Couldn't delete story", Toast.LENGTH_SHORT).show()
                        resume()
                    }
                }
            }
            .setNegativeButton("Cancel") { _, _ -> resume() }
            .setOnCancelListener { resume() }
            .show()
    }

    // ── Helpers ─────────────────────────────

    private fun loadAvatar(userId: String) {
        val initial = Nicknames.defaultFor(userId).firstOrNull() ?: '?'
        val color = resources.getColor(com.privatechat.app.R.color.primary, theme)
        val fallback = NotificationAvatarFactory.create(resources.displayMetrics.density, initial, color)
        binding.storyViewerAvatar.setImageBitmap(fallback)

        com.google.firebase.database.FirebaseDatabase
            .getInstance("https://private-chat-7a103-default-rtdb.asia-southeast1.firebasedatabase.app")
            .getReference("photos").child(userId)
            .addListenerForSingleValueEvent(object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                    val url = snapshot.getValue(String::class.java)
                    if (!url.isNullOrBlank() && !isFinishing && !isDestroyed) {
                        Glide.with(this@StoryViewerActivity).load(url).transform(CircleCrop()).into(binding.storyViewerAvatar)
                    }
                }
                override fun onCancelled(error: com.google.firebase.database.DatabaseError) = Unit
            })
    }

    private fun timeAgo(timestamp: Long): String {
        val diffMinutes = (System.currentTimeMillis() - timestamp) / 60000
        return when {
            diffMinutes < 1 -> "now"
            diffMinutes < 60 -> "${diffMinutes}m"
            diffMinutes < 1440 -> "${diffMinutes / 60}h"
            else -> "${diffMinutes / 1440}d"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        holdRunnable?.let { handler.removeCallbacks(it) }
        binding.storyVid.stopPlayback()
    }

    companion object {
        private const val EXTRA_START_USER_ID = "start_user_id"
        private const val IMAGE_DURATION_MS = 30_000L

        fun newIntent(context: android.content.Context, startUserId: String): Intent =
            Intent(context, StoryViewerActivity::class.java)
                .putExtra(EXTRA_START_USER_ID, startUserId)
    }
}
