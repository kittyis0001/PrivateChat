package com.privatechat.app.media

import android.content.Context
import android.net.Uri
import com.privatechat.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Uploads directly to Cloudinary from the device using an *unsigned*
 * upload preset — the only credential this needs (cloud name + preset
 * name) is safe to ship inside the app, unlike a signed-upload API
 * secret. This is deliberate: a signed upload would need a backend
 * endpoint to generate the signature, and the Render backend is
 * explicitly out of scope for this feature.
 *
 * Set BuildConfig.CLOUDINARY_CLOUD_NAME / CLOUDINARY_UPLOAD_PRESET in
 * app/build.gradle.kts (see the comment there) after creating an
 * unsigned upload preset at
 * https://cloudinary.com/console/settings/upload.
 */
object CloudinaryUploader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    class UploadException(message: String) : Exception(message)

    private fun requireConfigured(): Pair<String, String> {
        val cloudName = BuildConfig.CLOUDINARY_CLOUD_NAME
        val uploadPreset = BuildConfig.CLOUDINARY_UPLOAD_PRESET
        if (cloudName == "REPLACE-ME" || uploadPreset == "REPLACE-ME") {
            throw UploadException(
                "Cloudinary isn't configured yet — set CLOUDINARY_CLOUD_NAME and " +
                    "CLOUDINARY_UPLOAD_PRESET in app/build.gradle.kts."
            )
        }
        return cloudName to uploadPreset
    }

    // Wraps a multipart RequestBody and reports fractional (0f..1f)
    // upload progress as OkHttp actually writes bytes to the socket —
    // used by the chat image/video send flow's progress bar. Not used
    // by uploadAudio/the DP-photo call site, which don't need one.
    private class ProgressRequestBody(
        private val delegate: RequestBody,
        private val onProgress: (Float) -> Unit
    ) : RequestBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun isOneShot() = delegate.isOneShot()

        override fun writeTo(sink: BufferedSink) {
            var bytesWritten = 0L
            val total = contentLength()
            val countingSink = object : ForwardingSink(sink) {
                override fun write(source: Buffer, byteCount: Long) {
                    super.write(source, byteCount)
                    bytesWritten += byteCount
                    if (total > 0) onProgress((bytesWritten.toFloat() / total).coerceIn(0f, 1f))
                }
            }
            val bufferedCountingSink = countingSink.buffer()
            delegate.writeTo(bufferedCountingSink)
            bufferedCountingSink.flush()
        }
    }

    private fun secureUrlFrom(response: okhttp3.Response): String {
        val bodyString = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw UploadException("Cloudinary upload failed (${response.code}): $bodyString")
        }
        val secureUrl = JSONObject(bodyString).optString("secure_url", "")
        if (secureUrl.isEmpty()) {
            throw UploadException("Cloudinary response had no secure_url.")
        }
        return secureUrl
    }

    /**
     * Copies the picked image into the app's cache (content:// Uris
     * from the photo picker aren't directly readable as a File by
     * OkHttp's multipart body), uploads it, and returns Cloudinary's
     * secure_url. Runs entirely on Dispatchers.IO — call from a
     * coroutine.
     *
     * [onProgress] (0f..1f) is optional — the existing Change-DP call
     * site doesn't pass one, only the chat image-send flow does.
     */
    suspend fun uploadImage(
        context: Context,
        imageUri: Uri,
        onProgress: ((Float) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        val (cloudName, uploadPreset) = requireConfigured()

        val mimeType = context.contentResolver.getType(imageUri) ?: "image/*"
        val extension = when {
            mimeType.contains("png") -> ".png"
            mimeType.contains("webp") -> ".webp"
            mimeType.contains("gif") -> ".gif"
            else -> ".jpg"
        }
        val tempFile = File.createTempFile("chat_image_", extension, context.cacheDir)
        try {
            context.contentResolver.openInputStream(imageUri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            } ?: throw UploadException("Could not read the selected image.")

            var fileBody: RequestBody = tempFile.asRequestBody(mimeType.toMediaTypeOrNull())
            if (onProgress != null) {
                fileBody = ProgressRequestBody(fileBody) { fraction ->
                    android.os.Handler(android.os.Looper.getMainLooper()).post { onProgress(fraction) }
                }
            }
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("upload_preset", uploadPreset)
                .addFormDataPart("file", tempFile.name, fileBody)
                .build()

            val request = Request.Builder()
                .url("https://api.cloudinary.com/v1_1/$cloudName/image/upload")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response -> secureUrlFrom(response) }
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Same flow as [uploadImage] but for a picked video (jpg/png/webp/gif
     * go through the image pipeline above; mp4/mov/3gp go through this
     * one, Cloudinary's "video" resource type). Reuses the same unsigned
     * preset — Cloudinary's own dashboard scopes what an unsigned preset
     * may accept, independently per resource type.
     */
    suspend fun uploadVideo(
        context: Context,
        videoUri: Uri,
        onProgress: ((Float) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        val (cloudName, uploadPreset) = requireConfigured()

        val mimeType = context.contentResolver.getType(videoUri) ?: "video/mp4"
        val extension = when {
            mimeType.contains("quicktime") || mimeType.contains("mov") -> ".mov"
            mimeType.contains("3gpp") -> ".3gp"
            else -> ".mp4"
        }
        val tempFile = File.createTempFile("chat_video_", extension, context.cacheDir)
        try {
            context.contentResolver.openInputStream(videoUri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            } ?: throw UploadException("Could not read the selected video.")

            var fileBody: RequestBody = tempFile.asRequestBody(mimeType.toMediaTypeOrNull())
            if (onProgress != null) {
                fileBody = ProgressRequestBody(fileBody) { fraction ->
                    android.os.Handler(android.os.Looper.getMainLooper()).post { onProgress(fraction) }
                }
            }
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("upload_preset", uploadPreset)
                .addFormDataPart("file", tempFile.name, fileBody)
                .build()

            val request = Request.Builder()
                .url("https://api.cloudinary.com/v1_1/$cloudName/video/upload")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response -> secureUrlFrom(response) }
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Cloudinary auto-generates a JPG thumbnail for any uploaded video at
     * the same public ID — swapping the video URL's extension for .jpg
     * (and /video/upload/ is already in the URL) returns it directly, no
     * extra API call needed. Used for the video message bubble's
     * thumbnail and the picker preview.
     */
    fun videoThumbnailUrl(videoSecureUrl: String): String =
        videoSecureUrl.substringBeforeLast('.') + ".jpg"

    /**
     * Uploads a recorded voice message. Cloudinary treats audio as
     * part of its "video" resource type (audio files use the same
     * pipeline as video without a visual track — this is Cloudinary's
     * own convention, not something specific to this app), so this
     * hits the /video/upload endpoint rather than /image/upload,
     * reusing the same unsigned preset. Uploads directly from the
     * local recording file — MediaRecorder already writes a real File,
     * so no content-resolver copy is needed here unlike uploadImage.
     */
    suspend fun uploadAudio(audioFile: File): String = withContext(Dispatchers.IO) {
        val (cloudName, uploadPreset) = requireConfigured()

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("upload_preset", uploadPreset)
            .addFormDataPart(
                "file",
                audioFile.name,
                audioFile.asRequestBody("audio/mp4".toMediaTypeOrNull())
            )
            .build()

        val request = Request.Builder()
            .url("https://api.cloudinary.com/v1_1/$cloudName/video/upload")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response -> secureUrlFrom(response) }
    }
}
