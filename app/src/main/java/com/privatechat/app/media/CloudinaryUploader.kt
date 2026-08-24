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
import okhttp3.RequestBody.Companion.asRequestBody
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

    /**
     * Copies the picked image into the app's cache (content:// Uris
     * from the photo picker aren't directly readable as a File by
     * OkHttp's multipart body), uploads it, and returns Cloudinary's
     * secure_url. Runs entirely on Dispatchers.IO — call from a
     * coroutine.
     */
    suspend fun uploadImage(context: Context, imageUri: Uri): String = withContext(Dispatchers.IO) {
        val cloudName = BuildConfig.CLOUDINARY_CLOUD_NAME
        val uploadPreset = BuildConfig.CLOUDINARY_UPLOAD_PRESET
        if (cloudName == "REPLACE-ME" || uploadPreset == "REPLACE-ME") {
            throw UploadException(
                "Cloudinary isn't configured yet — set CLOUDINARY_CLOUD_NAME and " +
                    "CLOUDINARY_UPLOAD_PRESET in app/build.gradle.kts."
            )
        }

        val tempFile = File.createTempFile("dp_upload_", ".jpg", context.cacheDir)
        try {
            context.contentResolver.openInputStream(imageUri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            } ?: throw UploadException("Could not read the selected image.")

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("upload_preset", uploadPreset)
                .addFormDataPart(
                    "file",
                    tempFile.name,
                    tempFile.asRequestBody("image/*".toMediaTypeOrNull())
                )
                .build()

            val request = Request.Builder()
                .url("https://api.cloudinary.com/v1_1/$cloudName/image/upload")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw UploadException("Cloudinary upload failed (${response.code}): $bodyString")
                }
                val secureUrl = JSONObject(bodyString).optString("secure_url", "")
                if (secureUrl.isEmpty()) {
                    throw UploadException("Cloudinary response had no secure_url.")
                }
                secureUrl
            }
        } finally {
            tempFile.delete()
        }
    }
}
