package com.privatechat.app.ui.story

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.privatechat.app.data.Session
import com.privatechat.app.data.model.StoryEdit
import com.privatechat.app.data.repository.StoryRepository
import com.privatechat.app.databinding.ActivityStoryUploadBinding
import com.privatechat.app.media.CloudinaryUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * "Add to Your Story" — same-to-same layout/flow as the reference web
 * app's #storyUploadOverlay + the story-editor.js editor bolted on:
 * filters, text (font/size/color/bg/animation), stickers, freehand
 * draw, and brightness/contrast/blur adjust.
 *
 * Image stories bake every edit into the final uploaded image
 * (matches storyEditorExportImage exactly — filter+adjust+blur+draw+
 * text+stickers all flattened into one JPEG). Video stories can't be
 * re-encoded client-side, so their edits are stored as normalized
 * overlay data (StoryEdit) and rendered live on top of playback in
 * StoryViewerActivity instead — same split the reference itself uses
 * (storyEditorExportEditData).
 */
class StoryUploadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStoryUploadBinding
    private var pickedUri: Uri? = null
    private var pickedIsVideo = false
    private var isUploading = false

    private var currentFilter: StoryFilters.Filter = StoryFilters.ALL[0]
    private var adjBrightness = 100
    private var adjContrast = 100
    private var adjBlur = 0

    private var selectedFont = "sans-serif"
    private var selectedTextColor = "#ffffff"
    private var selectedTextBg = "transparent"
    private var selectedAnim = "none"

    private val toolButtons = mutableMapOf<String, TextView>()
    private val panels = mutableMapOf<String, View>()

    private val pickPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchPicker()
        }
    private val pickerLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) onMediaPicked(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStoryUploadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.storyUploadClose.setOnClickListener { if (!isUploading) handleBack() }
        binding.storyCancelButton.setOnClickListener { if (!isUploading) finish() }
        binding.storyPickButton.setOnClickListener { requestPick() }
        binding.storyPickPlaceholder.setOnClickListener { requestPick() }
        binding.storyShareButton.setOnClickListener { shareStory() }
        binding.storyEditToggleBtn.setOnClickListener { toggleEditorPanel() }

        setupToolRow()
        setupFilterPanel()
        setupTextPanel()
        setupStickerPanel()
        setupDrawPanel()
        setupAdjustPanel()
        selectTool("filter")

        onBackPressedDispatcher.addCallback(this) {
            if (isUploading) return@addCallback
            handleBack()
        }
    }

    private fun handleBack() {
        if (binding.storyEditorPanel.visibility == View.VISIBLE) {
            binding.storyEditorPanel.visibility = View.GONE
        } else {
            finish()
        }
    }

    // ── Pick ─────────────────────────────

    private fun requestPick() {
        val permission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val granted = ContextCompat.checkSelfPermission(this, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) launchPicker() else pickPermissionLauncher.launch(permission)
    }

    private fun launchPicker() {
        pickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
        )
    }

    private fun onMediaPicked(uri: Uri) {
        pickedUri = uri
        val mimeType = contentResolver.getType(uri) ?: ""
        pickedIsVideo = mimeType.startsWith("video")

        binding.storyPickPlaceholder.visibility = View.GONE
        if (pickedIsVideo) {
            binding.storyPreviewVideo.visibility = View.VISIBLE
            binding.storyPreviewImage.visibility = View.GONE
            val controller = MediaController(this)
            controller.setAnchorView(binding.storyPreviewVideo)
            binding.storyPreviewVideo.setMediaController(controller)
            binding.storyPreviewVideo.setVideoURI(uri)
            binding.storyPreviewVideo.setOnPreparedListener { it.isLooping = true; it.start() }
        } else {
            binding.storyPreviewImage.visibility = View.VISIBLE
            binding.storyPreviewVideo.visibility = View.GONE
            Glide.with(this).load(uri).into(binding.storyPreviewImage)
        }

        binding.storyCaptionInput.visibility = View.VISIBLE
        binding.storyShareButton.visibility = View.VISIBLE
        binding.storyRightToolbar.visibility = View.VISIBLE
        binding.storyPickButton.text = "📷  Change Photo / Video"
    }

    // ── Editor panel plumbing ─────────────────────────────

    private fun toggleEditorPanel() {
        if (pickedUri == null) return
        binding.storyEditorPanel.visibility =
            if (binding.storyEditorPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun setupToolRow() {
        val tools = listOf("filter" to "🎨 Filter", "text" to "Aa Text", "sticker" to "😊 Sticker", "draw" to "✏️ Draw", "adjust" to "⚙️ Adjust")
        tools.forEach { (id, label) ->
            val button = TextView(this).apply {
                text = label
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setTextColor(Color.parseColor("#B3FFFFFF"))
                setPadding(0, dp(6), 0, dp(6))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { selectTool(id) }
            }
            toolButtons[id] = button
            binding.storyToolRow.addView(button)
        }
        panels["filter"] = binding.storyPanelFilter
        panels["text"] = binding.storyPanelText
        panels["sticker"] = binding.storyPanelSticker
        panels["draw"] = binding.storyPanelDraw
        panels["adjust"] = binding.storyPanelAdjust
    }

    private fun selectTool(id: String) {
        panels.forEach { (key, view) -> view.visibility = if (key == id) View.VISIBLE else View.GONE }
        toolButtons.forEach { (key, button) ->
            button.setTextColor(if (key == id) Color.WHITE else Color.parseColor("#B3FFFFFF"))
        }
        binding.storyDrawView.drawingEnabled = (id == "draw")
    }

    // ── Filter panel ─────────────────────────────

    private fun setupFilterPanel() {
        StoryFilters.ALL.forEach { filter ->
            val chip = TextView(this).apply {
                text = filter.label
                textSize = 11f
                setTextColor(Color.WHITE)
                gravity = android.view.Gravity.CENTER
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setBackgroundResource(com.privatechat.app.R.drawable.bg_story_toolbar_button)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(8)
                }
                setOnClickListener { applyFilter(filter) }
            }
            binding.storyFilterList.addView(chip)
        }
    }

    private fun applyFilter(filter: StoryFilters.Filter) {
        currentFilter = filter
        applyLivePreviewMatrix()
    }

    private fun applyLivePreviewMatrix() {
        // Live preview only applies cleanly to the image case — a
        // VideoView is a raw Surface, not a drawable ImageView, so it
        // has no setColorFilter to hook into. The selection is still
        // fully captured either way; for video it's applied as a
        // best-effort overlay approximation in the viewer instead of a
        // pixel-accurate live preview here (documented in the PR).
        if (!pickedIsVideo) {
            val matrix = StoryFilters.buildColorMatrix(currentFilter, adjBrightness, adjContrast)
            binding.storyPreviewImage.colorFilter = ColorMatrixColorFilter(matrix)
        }
    }

    // ── Text panel ─────────────────────────────

    private fun setupTextPanel() {
        binding.storyFontSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, StoryFonts.ALL.map { it.label }
        )
        binding.storyFontSpinner.onItemSelectedListener = simpleSelectListener { pos ->
            selectedFont = StoryFonts.ALL[pos].id
        }

        val animOptions = listOf("none" to "None", "fade" to "Fade in", "slide" to "Slide up", "zoom" to "Zoom in")
        binding.storyAnimSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, animOptions.map { it.second }
        )
        binding.storyAnimSpinner.onItemSelectedListener = simpleSelectListener { pos ->
            selectedAnim = animOptions[pos].first
        }

        val colors = listOf("#ffffff", "#000000", "#ff3b30", "#ff9500", "#ffcc00", "#34c759", "#5ac8fa", "#007aff", "#af52de", "#ff2d78")
        colors.forEach { hex ->
            binding.storyTextColorRow.addView(colorDot(hex) { selectedTextColor = hex })
        }

        val bgOptions = listOf("transparent", "#000000", "#ffffff", "#ff3b30", "#007aff", "#34c759")
        bgOptions.forEach { hex ->
            binding.storyTextBgRow.addView(colorDot(hex, isNone = hex == "transparent") { selectedTextBg = hex })
        }

        binding.storyTextAddBtn.setOnClickListener {
            val text = binding.storyTextInput.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@setOnClickListener
            val sizeSp = binding.storyTextSizeSeek.progress.toFloat().coerceAtLeast(14f)
            binding.storyOverlayContainer.addText(text, selectedFont, sizeSp, selectedTextColor, selectedTextBg, selectedAnim)
            binding.storyTextInput.setText("")
        }
    }

    // ── Sticker panel ─────────────────────────────

    private fun setupStickerPanel() {
        val emojis = listOf(
            "😀", "😂", "🥰", "😍", "😎", "🤩", "😭", "😡", "🤔", "😴",
            "👍", "👎", "👏", "🙌", "🙏", "💪", "❤️", "🔥", "✨", "🎉",
            "🎂", "🎁", "🌸", "🌈", "☀️", "🌙", "⭐", "☁️", "🍕", "🍔",
            "☕", "🎵"
        )
        emojis.forEach { emoji ->
            val btn = TextView(this).apply {
                text = emoji
                textSize = 26f
                setPadding(dp(6), dp(6), dp(6), dp(6))
                setOnClickListener { binding.storyOverlayContainer.addSticker(emoji) }
            }
            binding.storyStickerRow.addView(btn)
        }
    }

    // ── Draw panel ─────────────────────────────

    private fun setupDrawPanel() {
        val colors = listOf("#ff3b30", "#ff9500", "#ffcc00", "#34c759", "#5ac8fa", "#007aff", "#af52de", "#ffffff", "#000000")
        colors.forEach { hex ->
            binding.storyDrawColorRow.addView(colorDot(hex) { binding.storyDrawView.color = Color.parseColor(hex) })
        }
        binding.storyDrawSizeSeek.setOnSeekBarChangeListener(simpleSeekListener { progress ->
            binding.storyDrawView.strokeWidthDp = progress.toFloat().coerceAtLeast(2f)
        })
        binding.storyDrawUndoBtn.setOnClickListener { binding.storyDrawView.undo() }
        binding.storyDrawClearBtn.setOnClickListener { binding.storyDrawView.clear() }
    }

    // ── Adjust panel ─────────────────────────────

    private fun setupAdjustPanel() {
        binding.storyAdjBrightnessSeek.setOnSeekBarChangeListener(simpleSeekListener { progress ->
            adjBrightness = progress
            applyLivePreviewMatrix()
        })
        binding.storyAdjContrastSeek.setOnSeekBarChangeListener(simpleSeekListener { progress ->
            adjContrast = progress
            applyLivePreviewMatrix()
        })
        binding.storyAdjBlurSeek.setOnSeekBarChangeListener(simpleSeekListener { progress ->
            // Blur is intentionally not live-previewed (a real box blur
            // on every slider tick would be janky on a decode-sized
            // bitmap) — it's applied once at bake/share time instead.
            // See StoryBlur's own doc comment.
            adjBlur = progress
        })
    }

    // ── Share ─────────────────────────────

    private fun shareStory() {
        val uri = pickedUri ?: return
        if (isUploading) return
        val currentUser = Session.currentUser() ?: return

        isUploading = true
        binding.storyUploadOverlay2.visibility = View.VISIBLE
        binding.storyUploadSpinner.progress = 0
        binding.storyShareButton.isEnabled = false

        val caption = binding.storyCaptionInput.text?.toString()?.trim()

        lifecycleScope.launch {
            try {
                val onProgress: (Float) -> Unit = { fraction ->
                    binding.storyUploadSpinner.progress = (fraction * 100).toInt()
                }

                if (pickedIsVideo) {
                    val mediaUrl = CloudinaryUploader.uploadVideo(applicationContext, uri, onProgress)
                    val edit = buildEditData()
                    postToFirebase(currentUser, "video", mediaUrl, caption, if (edit.isEmpty()) null else edit)
                } else {
                    val bakedUri = bakeImage(uri)
                    val mediaUrl = CloudinaryUploader.uploadImage(applicationContext, bakedUri, onProgress)
                    postToFirebase(currentUser, "image", mediaUrl, caption, null)
                }
            } catch (e: Exception) {
                isUploading = false
                binding.storyUploadOverlay2.visibility = View.GONE
                binding.storyShareButton.isEnabled = true
                Toast.makeText(this@StoryUploadActivity, e.message ?: "Upload failed. Please try again.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun postToFirebase(currentUser: String, type: String, mediaUrl: String, caption: String?, edit: StoryEdit?) {
        StoryRepository(currentUser).createStory(type, mediaUrl, caption, edit) { success ->
            if (success) {
                setResult(RESULT_OK)
                finish()
            } else {
                isUploading = false
                binding.storyUploadOverlay2.visibility = View.GONE
                binding.storyShareButton.isEnabled = true
                Toast.makeText(this, "Couldn't post your story. Try again.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun buildEditData(): StoryEdit = StoryEdit(
        filter = currentFilter.id,
        brightness = adjBrightness,
        contrast = adjContrast,
        blur = adjBlur,
        texts = binding.storyOverlayContainer.exportTexts(),
        stickers = binding.storyOverlayContainer.exportStickers(),
        drawStrokes = binding.storyDrawView.exportStrokes()
    )

    /** Flattens filter + adjust(brightness/contrast/blur) + draw
     * strokes + text/stickers into one final JPEG, matching the
     * reference's storyEditorExportImage exactly in spirit. Returns a
     * file:// Uri to a temp file in the app's cache dir. */
    private suspend fun bakeImage(sourceUri: Uri): Uri {
        val original = withContext(Dispatchers.IO) {
            contentResolver.openInputStream(sourceUri)?.use { BitmapFactory.decodeStream(it) }
        } ?: throw IllegalStateException("Couldn't read the selected image.")

        // Cap the longest side so baking/upload stays fast — plenty
        // for a story, matching typical chat-image sizing elsewhere in
        // this app.
        val maxDim = 1280
        val scale = (maxDim.toFloat() / maxOf(original.width, original.height)).coerceAtMost(1f)
        val targetW = (original.width * scale).toInt().coerceAtLeast(1)
        val targetH = (original.height * scale).toInt().coerceAtLeast(1)

        // Base image + filter/adjust color matrix — pure pixel work,
        // safe and worthwhile to do off the main thread.
        var canvasBitmap = withContext(Dispatchers.Default) {
            val resized = if (scale < 1f) Bitmap.createScaledBitmap(original, targetW, targetH, true) else original
            val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(StoryFilters.buildColorMatrix(currentFilter, adjBrightness, adjContrast))
            }
            Canvas(bmp).drawBitmap(resized, 0f, 0f, paint)
            bmp
        }

        // Blur is the genuinely expensive O(width*height*radius) step
        // — also pure pixel work, also safe off the main thread.
        if (adjBlur > 0) {
            val blurRadius = (adjBlur * targetW / 220).coerceAtLeast(1)
            canvasBitmap = withContext(Dispatchers.Default) { StoryBlur.apply(canvasBitmap, blurRadius) }
        }

        // Draw/text/sticker overlays read live View state (width,
        // height, child views) — that must happen back on the main
        // thread, which is exactly where execution resumes here after
        // the withContext(Default) blocks above return.
        val canvas = Canvas(canvasBitmap)
        binding.storyDrawView.drawOnto(canvas, targetW, targetH)
        binding.storyOverlayContainer.drawOnto(canvas, targetW, targetH)

        val file = File(cacheDir, "story_${System.currentTimeMillis()}.jpg")
        withContext(Dispatchers.IO) {
            FileOutputStream(file).use { out -> canvasBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out) }
        }
        return Uri.fromFile(file)
    }

    // ── Small helpers ─────────────────────────────

    private fun colorDot(hex: String, isNone: Boolean = false, onPick: () -> Unit): View {
        val size = dp(28)
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(8) }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(if (isNone) Color.parseColor("#33FFFFFF") else Color.parseColor(hex))
                setStroke(dp(1), Color.parseColor("#66FFFFFF"))
            }
            setOnClickListener { onPick() }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun simpleSeekListener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    private fun simpleSelectListener(onSelect: (Int) -> Unit) = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
            onSelect(position)
        }
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }

    override fun onDestroy() {
        super.onDestroy()
        if (binding.storyPreviewVideo.visibility == View.VISIBLE) {
            binding.storyPreviewVideo.stopPlayback()
        }
    }

    companion object {
        fun newIntent(context: android.content.Context): Intent = Intent(context, StoryUploadActivity::class.java)
    }
}
