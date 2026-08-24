package com.privatechat.app.ui.photo

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.privatechat.app.databinding.ActivityPhotoViewerBinding

/**
 * "Tapping any avatar opens an Instagram-style full-screen profile
 * picture viewer" — full image, dark background, pinch/double-tap
 * zoom, swipe down to close. The zoom/pan/dismiss gesture logic lives
 * in ZoomableImageView; this Activity just wires it up to a URL and
 * the exit transition.
 */
class PhotoViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoViewerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val photoUrl = intent.getStringExtra(EXTRA_PHOTO_URL)
        if (photoUrl.isNullOrBlank()) {
            finish()
            return
        }

        binding.photoViewerClose.setOnClickListener { finishWithFade() }

        binding.photoViewerImage.onDismiss = { finishWithFade() }
        binding.photoViewerImage.onDragProgress = { progress ->
            // Background dims out and the close button fades as the
            // user drags toward the dismiss threshold, so the gesture
            // reads as "pulling the photo away" rather than a hard cut.
            val alpha = 1f - progress * 0.6f
            binding.root.alpha = alpha
            binding.photoViewerClose.alpha = 1f - progress
        }

        loadImage(photoUrl, binding.photoViewerProgress)
    }

    private fun loadImage(url: String, progressBar: ProgressBar) {
        progressBar.visibility = View.VISIBLE
        Glide.with(this)
            .load(url)
            .listener(object : RequestListener<android.graphics.drawable.Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<android.graphics.drawable.Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    progressBar.visibility = View.GONE
                    return false
                }

                override fun onResourceReady(
                    resource: android.graphics.drawable.Drawable,
                    model: Any,
                    target: Target<android.graphics.drawable.Drawable>,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    progressBar.visibility = View.GONE
                    // Center once Glide has actually delivered the
                    // bitmap and the view has real dimensions — doing
                    // this before either is ready would center against
                    // a zero-sized view.
                    binding.photoViewerImage.post { binding.photoViewerImage.resetToCenter() }
                    return false
                }
            })
            .into(binding.photoViewerImage)
    }

    private fun finishWithFade() {
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    companion object {
        private const val EXTRA_PHOTO_URL = "photo_url"

        fun newIntent(context: android.content.Context, photoUrl: String): android.content.Intent =
            android.content.Intent(context, PhotoViewerActivity::class.java)
                .putExtra(EXTRA_PHOTO_URL, photoUrl)
    }
}
