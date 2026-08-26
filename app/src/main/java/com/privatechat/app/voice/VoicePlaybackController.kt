package com.privatechat.app.voice

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper

/**
 * Single shared MediaPlayer for ALL voice playback in the app — the
 * compose bar's own pre-send preview and every message bubble's play
 * button. Starting a new source stops whatever was previously
 * playing, matching WhatsApp's one-voice-note-at-a-time behavior, and
 * avoids each RecyclerView row owning its own MediaPlayer (which
 * would leak across view recycling and allow overlapping playback).
 */
object VoicePlaybackController {

    private var mediaPlayer: MediaPlayer? = null
    private var currentSource: String? = null
    private var progressHandler: Handler? = null
    private var progressRunnable: Runnable? = null

    // source = a Cloudinary URL for a bubble, or a local file path for
    // the compose-bar preview — whichever is currently active, so
    // callers (bubbles, the preview bar) can tell if THEY are the one
    // playing right now and update their own UI accordingly.
    var onStateChanged: ((source: String?, isPlaying: Boolean, positionMs: Int, durationMs: Int) -> Unit)? = null

    fun isPlaying(source: String): Boolean = currentSource == source && mediaPlayer?.isPlaying == true

    /** Toggles play/pause for [source] (a URL or a local file path — MediaPlayer.setDataSource(String) accepts either transparently). */
    fun togglePlayback(source: String) {
        val player = mediaPlayer
        if (currentSource == source && player != null) {
            if (player.isPlaying) {
                player.pause()
                stopProgressUpdates()
                onStateChanged?.invoke(source, false, player.currentPosition, player.duration.coerceAtLeast(0))
            } else {
                player.start()
                startProgressUpdates(source)
            }
            return
        }

        // Switching to a different source — tear down whatever was
        // playing before starting the new one.
        stop()

        val newPlayer = MediaPlayer()
        try {
            newPlayer.setDataSource(source)
            newPlayer.setOnPreparedListener {
                currentSource = source
                mediaPlayer = newPlayer
                newPlayer.start()
                startProgressUpdates(source)
            }
            newPlayer.setOnCompletionListener { completed ->
                stopProgressUpdates()
                onStateChanged?.invoke(source, false, 0, completed.duration.coerceAtLeast(0))
                currentSource = null
                mediaPlayer = null
                completed.release()
            }
            newPlayer.prepareAsync()
        } catch (e: Exception) {
            newPlayer.release()
        }
    }

    /** Stops and releases the current player entirely — call when a bubble is unbound/scrolled away or the compose-bar recording is discarded. */
    fun stop() {
        stopProgressUpdates()
        mediaPlayer?.apply {
            try {
                if (isPlaying) stop()
            } catch (e: IllegalStateException) {
                // Already stopped/released — nothing to do.
            }
            release()
        }
        mediaPlayer = null
        currentSource = null
    }

    fun stopIfSource(source: String) {
        if (currentSource == source) stop()
    }

    private fun startProgressUpdates(source: String) {
        stopProgressUpdates()
        val handler = Handler(Looper.getMainLooper())
        progressHandler = handler
        val runnable = object : Runnable {
            override fun run() {
                val player = mediaPlayer ?: return
                if (currentSource != source) return
                onStateChanged?.invoke(
                    source,
                    player.isPlaying,
                    player.currentPosition,
                    player.duration.coerceAtLeast(0)
                )
                handler.postDelayed(this, 200)
            }
        }
        progressRunnable = runnable
        handler.post(runnable)
    }

    private fun stopProgressUpdates() {
        progressRunnable?.let { progressHandler?.removeCallbacks(it) }
        progressRunnable = null
    }
}
