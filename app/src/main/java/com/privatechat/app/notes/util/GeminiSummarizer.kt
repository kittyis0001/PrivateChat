package com.privatechat.app.notes.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * AI summary via the Gemini API. The user's own API key is stored in
 * private SharedPreferences; notes are sent to Google's server only
 * when the user taps "AI summary". No key = no summary, honestly
 * reported (never a fake summary).
 */
object GeminiSummarizer {
    private const val PREFS = "kitty_notes_ai"
    private const val KEY_API_KEY = "gemini_api_key"
    private const val MODEL = "gemini-2.0-flash"

    fun getApiKey(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }

    fun saveApiKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    suspend fun summarize(context: Context, text: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val key = getApiKey(context) ?: throw IllegalStateException("NO_API_KEY")
                val prompt = "Summarize this note in 3 short lines in Bengali, " +
                    "keeping the key facts:\n\n" + text.take(6000)
                val bodyJson = JSONObject()
                    .put(
                        "contents", JSONArray().put(
                            JSONObject().put(
                                "parts", JSONArray().put(
                                    JSONObject().put("text", prompt)
                                )
                            )
                        )
                    ).toString()
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url(
                        "https://generativelanguage.googleapis.com/v1beta/models/" +
                            "$MODEL:generateContent?key=$key"
                    )
                    .post(bodyJson.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                    val out = JSONObject(raw)
                        .getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                        .getString("text").trim()
                    if (out.isBlank()) throw IllegalStateException("Empty summary")
                    out
                }
            }
        }
}
