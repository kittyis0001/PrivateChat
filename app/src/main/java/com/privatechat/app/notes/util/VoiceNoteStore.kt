package com.privatechat.app.notes.util

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** Audio files + MediaRecorder/MediaPlayer helpers for voice notes. */
object VoiceNoteStore {
    const val DIR_NAME = "voice_notes"

    fun dir(context: Context): File = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    fun newFile(context: Context, noteId: Long): File =
        File(dir(context), "${noteId}_${System.currentTimeMillis()}.m4a")

    fun createRecorder(context: Context, file: File): MediaRecorder {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        recorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128_000)
            setAudioSamplingRate(44_100)
            setOutputFile(file.absolutePath)
        }
        return recorder
    }

    fun createPlayer(file: File): MediaPlayer = MediaPlayer().apply {
        setDataSource(file.absolutePath)
        prepare()
    }

    fun formatDuration(ms: Long): String {
        val totalSeconds = (ms / 1000).toInt()
        return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
    }

    fun deleteFile(context: Context, fileName: String) {
        File(dir(context), fileName).delete()
    }
}
