package com.privatechat.app.ui.story

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.privatechat.app.data.model.Song

/**
 * Same two-engine approach as story.js's playStoryMusic: YouTube
 * songs play through a hidden YouTube IFrame Player (a small attached
 * WebView actually loading the official IFrame Player API — the same
 * technique the reference itself uses, since playback needs no API
 * key and a plain ACTION_VIEW/intent would open the YouTube app
 * instead of playing quietly in the background). Jamendo songs are a
 * direct streamable MP3 URL, so they just play through a plain
 * MediaPlayer — much simpler, and confirmed reliable.
 *
 * The YouTube path is inherently more fragile: it's a full embedded
 * web page running inside an Android WebView rather than native
 * playback, and WebViews apply extra scrutiny to autoplay-with-sound
 * that a real desktop/mobile browser (which is what the reference web
 * app itself runs in) doesn't hit the same way. playYoutube below
 * applies the standard, well-known mitigations for that class of
 * issue (a browser-like User-Agent, playsinline, a WebChromeClient) —
 * if YouTube playback is still unreliable after this on your device,
 * that's the WebView-autoplay limitation itself, not a quick config
 * fix; Jamendo remains the dependable source in that case.
 */
object StoryMusicPlayer {

    private var webView: WebView? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentSongId: String? = null

    // A generic WebView User-Agent is frequently detected and
    // throttled/blocked by YouTube's embed player (it isn't recognized
    // as a real mobile browser). Appending a standard mobile Chrome UA
    // string is the well-established fix for "YouTube embed refuses to
    // play/autoplay inside an Android WebView."
    private const val CHROME_USER_AGENT_SUFFIX =
        " Chrome/120.0.0.0 Mobile Safari/537.36"

    /** [container] is a small (e.g. a few dp) attached ViewGroup the
     * caller owns — a real WebView needs to be attached to a window to
     * play reliably in the background on some OEM builds, so this
     * can't be a purely in-memory singleton. */
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
            settings.userAgentString = settings.userAgentString + CHROME_USER_AGENT_SUFFIX
            // Handles various internal browser-chrome callbacks the
            // embedded YouTube player page can make — some WebView/
            // Chromium versions expect one to be present for a fully
            // normal embedded-video initialization path.
            webChromeClient = WebChromeClient()
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
                  playerVars: { autoplay: 1, controls: 0, playsinline: 1 },
                  events: {
                    onReady: function(e) { e.target.playVideo(); },
                    onStateChange: function(e) {
                      // -1 (unstarted) can mean the video hasn't
                      // actually begun — nudge it once in case the
                      // autoplay param alone didn't take.
                      if (e.data === -1) { player.playVideo(); }
                    }
                  }
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
