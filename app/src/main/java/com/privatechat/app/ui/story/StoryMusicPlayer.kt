package com.privatechat.app.ui.story

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import com.privatechat.app.BuildConfig
import com.privatechat.app.data.model.Song
import com.privatechat.app.notification.NotificationApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Plays a story's selected song. Both sources now go through the same
 * simple, reliable path — a plain MediaPlayer streaming a direct audio
 * URL — rather than embedding an actual YouTube player.
 *
 * That wasn't the original approach for YouTube: it first played
 * through a hidden WebView running the official YouTube IFrame Player
 * API (the same technique the reference web app itself uses in a real
 * browser). In practice that turned out to be too unreliable for
 * silent background autoplay inside an Android WebView specifically —
 * WebViews apply extra scrutiny to autoplay-with-sound that a real
 * browser doesn't hit the same way, and a round of standard
 * mitigations (a browser User-Agent, a WebChromeClient, playsinline)
 * didn't fix it. So YouTube songs now resolve to a direct stream URL
 * server-side (see backend/services/music.js's own comment on the
 * real trade-offs of that — it's not a "set once and forget" fix, and
 * Jamendo remains the more dependable source if this ever gives
 * trouble) and then play exactly like a Jamendo track.
 *
 * A singleton (like VoicePlaybackController elsewhere in this app) so
 * starting a new song always cleanly stops whatever was playing
 * before, from wherever in the app that was. [container] is accepted
 * for API compatibility with call sites built when YouTube used a
 * WebView that needed to stay attached to a window — no longer
 * strictly required now that both sources are a plain MediaPlayer,
 * but harmless to keep passing.
 */
object StoryMusicPlayer {

    private var mediaPlayer: MediaPlayer? = null
    private var currentSongId: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun play(container: ViewGroup, song: Song) {
        stop(container)
        currentSongId = song.id()
        when (song.source) {
            "jamendo" -> song.audioUrl?.let { playDirectUrl(it, song.id()) }
            "youtube" -> song.videoId?.let { resolveAndPlayYoutube(it, song.id()) }
        }
    }

    private fun resolveAndPlayYoutube(videoId: String, songId: String) {
        scope.launch {
            val audioUrl = try {
                val response = NotificationApiClient.get().resolveYoutubeAudio(BuildConfig.BACKEND_API_SECRET, videoId)
                if (response.isSuccessful) response.body()?.audioUrl else null
            } catch (e: Exception) {
                null
            }
            mainHandler.post {
                // The user may have already switched to a different
                // song (or stopped altogether) by the time this
                // network call returns — don't let a slow, stale
                // resolution start playing over whatever's current now.
                if (currentSongId == songId && audioUrl != null) {
                    playDirectUrl(audioUrl, songId)
                }
            }
        }
    }

    private fun playDirectUrl(url: String, songId: String) {
        val mp = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setDataSource(url)
            setOnPreparedListener { if (currentSongId == songId) it.start() }
            setOnErrorListener { _, _, _ -> true }
            prepareAsync()
        }
        mediaPlayer = mp
    }

    fun pause() {
        mediaPlayer?.let { if (it.isPlaying) it.pause() }
    }

    fun resume() {
        mediaPlayer?.let { if (!it.isPlaying) it.start() }
    }

    fun stop(container: ViewGroup? = null) {
        mediaPlayer?.let {
            try { it.stop() } catch (e: IllegalStateException) { /* not started */ }
            it.release()
        }
        mediaPlayer = null
        currentSongId = null
    }

    fun isPlaying(songId: String): Boolean = currentSongId == songId
}
