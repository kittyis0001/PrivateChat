package com.privatechat.app.ui.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.MediaController
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.privatechat.app.databinding.ActivityMediaPreviewBinding
import com.privatechat.app.media.CloudinaryUploader
import kotlinx.coroutines.launch

/**
 * WhatsApp-style "preview before send" screen for the camera-icon media
 * flow: shows the picked image or video full-screen with an optional
 * caption field, uploads to the existing Cloudinary config on Send
 * (with a visible progress bar), and hands the resulting secure_url +
 * caption back to ChatActivity via setResult — ChatActivity is the one
 * that actually writes the Firebase message, same as every other send
 * path in the app, so this screen never touches Firebase directly.
 */
class MediaPreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMediaPreviewBinding
    private var isUploading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val mediaUriString = intent.getStringExtra(EXTRA_MEDIA_URI)
        val isVideo = intent.getStringExtra(EXTRA_MEDIA_TYPE) == TYPE_VIDEO
        if (mediaUriString.isNullOrBlank()) {
            finish()
            return
        }
        val mediaUri = Uri.parse(mediaUriString)

        if (isVideo) {
            binding.previewVideo.visibility = View.VISIBLE
            val controller = MediaController(this)
            controller.setAnchorView(binding.previewVideo)
            binding.previewVideo.setMediaController(controller)
            binding.previewVideo.setVideoURI(mediaUri)
            binding.previewVideo.setOnPreparedListener { it.isLooping = true }
            binding.previewVideo.setOnErrorListener { _, _, _ ->
                Toast.makeText(this, "Couldn't preview this video", Toast.LENGTH_SHORT).show()
                true
            }
        } else {
            binding.previewImage.visibility = View.VISIBLE
            Glide.with(this).load(mediaUri).into(binding.previewImage)
        }

        binding.previewClose.setOnClickListener {
            if (!isUploading) finishWithFade()
        }

        binding.previewSend.setOnClickListener {
            if (isUploading) return@setOnClickListener
            uploadAndReturn(mediaUri, isVideo)
        }
    }

    private fun uploadAndReturn(mediaUri: Uri, isVideo: Boolean) {
        isUploading = true
        binding.previewUploadOverlay.visibility = View.VISIBLE
        binding.previewUploadSpinner.progress = 0
        binding.previewSend.isEnabled = false
        binding.previewCaptionInput.isEnabled = false

        val caption = binding.previewCaptionInput.text?.toString()?.trim().orEmpty()

        lifecycleScope.launch {
            try {
                val onProgress: (Float) -> Unit = { fraction ->
                    binding.previewUploadSpinner.progress = (fraction * 100).toInt()
                }
                val url = if (isVideo) {
                    CloudinaryUploader.uploadVideo(applicationContext, mediaUri, onProgress)
                } else {
                    CloudinaryUploader.uploadImage(applicationContext, mediaUri, onProgress)
                }
                val result = Intent().apply {
                    putExtra(RESULT_MEDIA_URL, url)
                    putExtra(RESULT_MEDIA_TYPE, if (isVideo) TYPE_VIDEO else TYPE_IMAGE)
                    putExtra(RESULT_CAPTION, caption)
                }
                setResult(RESULT_OK, result)
                finish()
            } catch (e: Exception) {
                // Upload failed — surface it and let the user retry or
                // cancel, rather than crash or silently drop the message.
                isUploading = false
                binding.previewUploadOverlay.visibility = View.GONE
                binding.previewSend.isEnabled = true
                binding.previewCaptionInput.isEnabled = true
                Toast.makeText(
                    this@MediaPreviewActivity,
                    e.message ?: "Upload failed. Please try again.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun finishWithFade() {
        setResult(RESULT_CANCELED)
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::binding.isInitialized && binding.previewVideo.visibility == View.VISIBLE) {
            binding.previewVideo.stopPlayback()
        }
    }

    companion object {
        private const val EXTRA_MEDIA_URI = "media_uri"
        private const val EXTRA_MEDIA_TYPE = "media_type"
        const val RESULT_MEDIA_URL = "result_media_url"
        const val RESULT_MEDIA_TYPE = "result_media_type"
        const val RESULT_CAPTION = "result_caption"
        const val TYPE_IMAGE = "image"
        const val TYPE_VIDEO = "video"

        fun newIntent(context: Context, mediaUri: Uri, isVideo: Boolean): Intent =
            Intent(context, MediaPreviewActivity::class.java)
                .putExtra(EXTRA_MEDIA_URI, mediaUri.toString())
                .putExtra(EXTRA_MEDIA_TYPE, if (isVideo) TYPE_VIDEO else TYPE_IMAGE)
    }
}
