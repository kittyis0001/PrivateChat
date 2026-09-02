package com.privatechat.app.ui.story

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.MediaController
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.privatechat.app.data.Session
import com.privatechat.app.data.repository.StoryRepository
import com.privatechat.app.databinding.ActivityStoryUploadBinding
import com.privatechat.app.media.CloudinaryUploader
import kotlinx.coroutines.launch

/**
 * "Add to Your Story" — same-to-same layout/flow as the reference
 * web app's #storyUploadOverlay (Phase 1: photo/video + caption only;
 * the music button and editor slot into this same screen in later
 * PRs, see the layout's own comments for where).
 */
class StoryUploadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStoryUploadBinding
    private var pickedUri: Uri? = null
    private var pickedIsVideo = false
    private var isUploading = false

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

        binding.storyUploadClose.setOnClickListener { if (!isUploading) finish() }
        binding.storyCancelButton.setOnClickListener { if (!isUploading) finish() }
        binding.storyPickButton.setOnClickListener { requestPick() }
        binding.storyPickPlaceholder.setOnClickListener { requestPick() }
        binding.storyShareButton.setOnClickListener { shareStory() }
    }

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
        binding.storyPickButton.text = "📷  Change Photo / Video"
    }

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
                val mediaUrl = if (pickedIsVideo) {
                    CloudinaryUploader.uploadVideo(applicationContext, uri, onProgress)
                } else {
                    CloudinaryUploader.uploadImage(applicationContext, uri, onProgress)
                }

                StoryRepository(currentUser).createStory(
                    type = if (pickedIsVideo) "video" else "image",
                    mediaUrl = mediaUrl,
                    caption = caption
                ) { success ->
                    if (success) {
                        setResult(RESULT_OK)
                        finish()
                    } else {
                        isUploading = false
                        binding.storyUploadOverlay2.visibility = View.GONE
                        binding.storyShareButton.isEnabled = true
                        Toast.makeText(this@StoryUploadActivity, "Couldn't post your story. Try again.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                isUploading = false
                binding.storyUploadOverlay2.visibility = View.GONE
                binding.storyShareButton.isEnabled = true
                Toast.makeText(this@StoryUploadActivity, e.message ?: "Upload failed. Please try again.", Toast.LENGTH_LONG).show()
            }
        }
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
