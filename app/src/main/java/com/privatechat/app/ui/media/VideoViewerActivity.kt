package com.privatechat.app.ui.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.MediaController
import androidx.appcompat.app.AppCompatActivity
import com.privatechat.app.databinding.ActivityVideoViewerBinding

/**
 * Full-screen video message playback — tapping a video bubble opens
 * this, mirroring PhotoViewerActivity's role for image bubbles. Uses
 * the platform's own VideoView + MediaController (play/pause/seek)
 * rather than pulling in a new player dependency, matching the "reuse
 * what's already in the project" scope for this feature.
 */
class VideoViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoViewerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL)
        if (videoUrl.isNullOrBlank()) {
            finish()
            return
        }

        binding.videoViewerClose.setOnClickListener { finishWithFade() }

        val controller = MediaController(this)
        controller.setAnchorView(binding.videoViewerPlayer)
        binding.videoViewerPlayer.setMediaController(controller)
        binding.videoViewerPlayer.setVideoURI(Uri.parse(videoUrl))

        binding.videoViewerProgress.visibility = View.VISIBLE
        binding.videoViewerPlayer.setOnPreparedListener {
            binding.videoViewerProgress.visibility = View.GONE
            binding.videoViewerPlayer.start()
        }
        binding.videoViewerPlayer.setOnErrorListener { _, _, _ ->
            binding.videoViewerProgress.visibility = View.GONE
            android.widget.Toast.makeText(this, "Couldn't play this video", android.widget.Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun finishWithFade() {
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop playback rather than let a background MediaPlayer linger
        // past the Activity's own lifecycle.
        if (::binding.isInitialized) binding.videoViewerPlayer.stopPlayback()
    }

    companion object {
        private const val EXTRA_VIDEO_URL = "video_url"

        fun newIntent(context: Context, videoUrl: String): Intent =
            Intent(context, VideoViewerActivity::class.java)
                .putExtra(EXTRA_VIDEO_URL, videoUrl)
    }
}
