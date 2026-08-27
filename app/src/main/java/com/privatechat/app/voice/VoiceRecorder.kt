package com.privatechat.app.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.io.IOException

/**
 * Records a voice message to a local .m4a file (AAC in an MP4
 * container — small file size, broadly compatible, and the format
 * Cloudinary's audio pipeline accepts without any server-side
 * transcoding). One instance is used per record-then-preview cycle.
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null

    var outputFile: File? = null
        private set

    /** Starts recording to a fresh temp file in the app's cache dir. Returns false on failure (mic busy, device quirk) instead of crashing the compose bar. */
    fun start(): Boolean {
        val file = File.createTempFile("voice_", ".m4a", context.cacheDir)
        outputFile = file
        return try {
            val newRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            newRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64000)
                setAudioSamplingRate(44100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = newRecorder
            true
        } catch (e: IOException) {
            releaseRecorder()
            false
        } catch (e: RuntimeException) {
            // MediaRecorder throws RuntimeException from start()/prepare()
            // if the mic is already in use by another app or on certain
            // device/driver quirks — treated as a clean failure rather
            // than crashing the compose bar.
            releaseRecorder()
            false
        }
    }

    /** Stops recording; [outputFile] is ready for preview/upload on success. Returns false if nothing usable was actually captured. */
    fun stop(): Boolean {
        val current = recorder ?: return false
        return try {
            current.stop()
            true
        } catch (e: RuntimeException) {
            // stop() throws if called before any audio was actually
            // captured (e.g. tapped stop within a few ms of starting) —
            // the output file in that case is invalid/empty.
            outputFile?.delete()
            false
        } finally {
            releaseRecorder()
        }
    }

    /** Discards the in-progress or just-finished recording entirely. */
    fun cancel() {
        try {
            recorder?.stop()
        } catch (e: RuntimeException) {
            // Fine to ignore — discarding regardless.
        }
        releaseRecorder()
        outputFile?.delete()
        outputFile = null
    }

    private fun releaseRecorder() {
        recorder?.release()
        recorder = null
    }

    /**
     * Current peak amplitude (0..32767 raw MediaRecorder scale) since
     * the last call — for the live waveform. Returns null while not
     * actively recording, or if the platform call itself fails
     * (harmless — the waveform just skips a sample rather than crash).
     */
    fun currentAmplitude(): Int? = try {
        recorder?.maxAmplitude
    } catch (e: RuntimeException) {
        null
    }
}
