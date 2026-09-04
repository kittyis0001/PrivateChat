package com.privatechat.app.ui.story

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.view.ViewGroup
import android.webkit.WebView
import com.privatechat.app.data.model.Song

/**
 * Same two-engine approach as story.js's playStoryMusic: YouTube
 * songs play through a hidden YouTube IFrame Player (here, a 1dp
 * WebView actually loading the official IFrame Player API — the same
 * technique the reference itself uses, since playback needs no API
 * key and a plain ACTION_VIEW/intent would open the YouTube app
 * instead of playing quietly in the background). Jamendo songs are a
 * direct streamable MP3 URL, so they just play through a plain
 * MediaPlayer.
 *
 * A singleton (like VoicePlaybackController elsewhere in this app) so
 * starting a new song always cleanly stops whatever was playing
 * before, from wherever in the app that was.
 */
object StoryMusicPlayer {

    private var webView: WebView? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentSongId: String? = null

    /** [container] is a small (e.g. 1dp) attached ViewGroup the caller
     * owns — a real WebView needs to be attached to a window to play
     * reliably in the background on some OEM builds, so this can't be
     * a purely in-memory singleton. */
    fun play(container: ViewGroup, song: Song) {
        stop(container)
        currentSongId = song.id()
        when (song.source) {
            "youtube" -> song.videoId?.let { playYoutube(container, it) }
            "jamendo" -> song.audioUrl?.let { playJamendo(it) }
        }
    }

    private fun playYoutube(container: ViewGroup, videoId: String) {
        val context = container.context.applicationContext
        val wv = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.domStorageEnabled = true
        }
        webView = wv
        container.removeAllViews()
        container.addView(wv)

        val html = """
            <html><body style="margin:0;background:#000">
            <div id="p"></div>
            <script src="https://www.youtube.com/iframe_api"></script>
            <script>
              var player;
              function onYouTubeIframeAPIReady() {
                player = new YT.Player('p', {
                  height: '1', width: '1', videoId: '$videoId',
                  playerVars: { autoplay: 1, controls: 0 },
                  events: { onReady: function(e) { e.target.playVideo(); } }
                });
              }
            </script>
            </body></html>
        """.trimIndent()
        wv.loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "utf-8", null)
    }

    private fun playJamendo(url: String) {
        val mp = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setDataSource(url)
            setOnPreparedListener { it.start() }
            setOnErrorListener { _, _, _ -> true }
            prepareAsync()
        }
        mediaPlayer = mp
    }

    fun pause() {
        mediaPlayer?.let { if (it.isPlaying) it.pause() }
        webView?.evaluateJavascript("if (window.player) player.pauseVideo();", null)
    }

    fun resume() {
        mediaPlayer?.let { if (!it.isPlaying) it.start() }
        webView?.evaluateJavascript("if (window.player) player.playVideo();", null)
    }

    fun stop(container: ViewGroup? = null) {
        mediaPlayer?.let {
            try { it.stop() } catch (e: IllegalStateException) { /* not started */ }
            it.release()
        }
        mediaPlayer = null
        webView?.let {
            container?.removeView(it)
            it.destroy()
        }
        webView = null
        currentSongId = null
    }

    fun isPlaying(songId: String): Boolean = currentSongId == songId
}
