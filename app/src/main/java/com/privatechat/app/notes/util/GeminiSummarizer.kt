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
    private const val KEY_SUMMARY_LANG = "summary_lang"
    // gemini-2.0-flash was shut down by Google on 2026-06-01, so it
    // 404s for every key. gemini-3.5-flash-lite is the current GA
    // replacement (free tier: 500 requests/day).
    private const val MODEL = "gemini-3.5-flash-lite"

    fun getApiKey(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }

    fun saveApiKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    /** Last chosen summary language: "bn" (default) or "en". */
    fun getSummaryLang(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SUMMARY_LANG, "bn") ?: "bn"

    fun saveSummaryLang(context: Context, lang: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SUMMARY_LANG, lang).apply()
    }

    /**
     * Title for a scanned note: Gemini reads the text and gives a
     * short title in the text's own language when an API key is
     * saved; without a key (or when the call fails) the first
     * content line is the title, exactly like a hand-written note.
     * Never throws — the local title is always the fallback.
     */
    suspend fun smartTitle(context: Context, text: String): String {
        val local = NoteSmart.autoTitle("", text)
        val key = getApiKey(context) ?: return local
        if (text.trim().length < 40) return local
        return try {
            generateTitle(key, text).takeIf { it.isNotBlank() } ?: local
        } catch (e: Exception) {
            local
        }
    }

    private suspend fun generateTitle(key: String, text: String): String =
        withContext(Dispatchers.IO) {
            val prompt = "Give a short title (max 8 words) for this note, " +
                "in the same language as the text. Reply with only the " +
                "title, no quotes, no extra words:\n\n" + text.take(2000)
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
                JSONObject(raw)
                    .getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                    .getString("text").trim()
                    .removeSurrounding("\"").removeSurrounding("'")
                    .lineSequence().firstOrNull { it.isNotBlank() }
                    ?.trim()?.take(60)
                    ?: throw IllegalStateException("Empty title")
            }
        }

    suspend fun summarize(context: Context, text: String, lang: String = "bn"): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val key = getApiKey(context) ?: throw IllegalStateException("NO_API_KEY")
                val langName = if (lang == "en") "English" else "Bengali"
                val prompt = "Summarize this note in 3 short lines in $langName, " +
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
