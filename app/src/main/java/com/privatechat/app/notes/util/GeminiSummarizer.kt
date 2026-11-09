package com.privatechat.app.notes.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
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

    // ---------------- newer AI features (one shared call) ----------------

    data class CategorySuggestion(val category: String, val tags: List<String>)

    /** One Gemini text call, shared by the newer features. Throws on failure. */
    private suspend fun generateText(key: String, prompt: String): String {
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
        return callGemini(key, bodyJson)
    }

    /**
     * Same call, but with a JPEG photo attached so Gemini can SEE the
     * page (vision). The model is multimodal, so the same endpoint
     * accepts the image as an inlineData part. Throws on failure.
     */
    private suspend fun generateWithImage(key: String, prompt: String, jpegBytes: ByteArray): String {
        val parts = JSONArray()
            .put(JSONObject().put("text", prompt))
            .put(
                JSONObject().put(
                    "inlineData", JSONObject()
                        .put("mimeType", "image/jpeg")
                        .put("data", Base64.encodeToString(jpegBytes, Base64.NO_WRAP))
                )
            )
        val bodyJson = JSONObject()
            .put(
                "contents", JSONArray().put(
                    JSONObject().put("parts", parts)
                )
            ).toString()
        return callGemini(key, bodyJson)
    }

    /** The HTTP call + reply parsing shared by [generateText] and [generateWithImage]. */
    private suspend fun callGemini(key: String, bodyJson: String): String =
        withContext(Dispatchers.IO) {
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
                if (out.isBlank()) throw IllegalStateException("Empty reply")
                out
            }
        }

    /**
     * Auto category + tags: picks the best existing category for the
     * note, or proposes one short new name when none fits, plus 2-4
     * short tags in the note's language. Never throws — failure is a
     * Result, and the caller falls back to doing nothing.
     */
    suspend fun suggestCategory(
        context: Context,
        text: String,
        existing: List<String>
    ): Result<CategorySuggestion> =
        withContext(Dispatchers.IO) {
            runCatching {
                val key = getApiKey(context) ?: throw IllegalStateException("NO_API_KEY")
                val existingJson = existing.joinToString(", ") { "\"$it\"" }
                val prompt = "This is a note. Pick the best category for it from this list: " +
                    "[$existingJson]. If none fits, propose one short new category name " +
                    "(max 3 words). Also suggest 2-4 short tags in the note's language. " +
                    "Reply with ONLY this JSON, no other words: " +
                    "{\"category\": \"...\", \"tags\": [\"...\", \"...\"]}\n\n" + text.take(3000)
                val raw = generateText(key, prompt)
                    .removePrefix("```json").removePrefix("```")
                    .removeSuffix("```").trim()
                val obj = JSONObject(raw)
                val category = obj.optString("category").trim()
                if (category.isBlank()) throw IllegalStateException("Empty category")
                val tags = mutableListOf<String>()
                val tagsArr = obj.optJSONArray("tags")
                if (tagsArr != null) {
                    for (i in 0 until tagsArr.length()) {
                        val t = tagsArr.optString(i).trim().removePrefix("#")
                        if (t.isNotBlank() && !t.contains(",")) tags.add(t)
                        if (tags.size >= 4) break
                    }
                }
                CategorySuggestion(category.take(30), tags)
            }
        }

    /**
     * Writes a whole note from one line of idea. Returns title + body;
     * the caller opens the editor with them (nothing is saved silently).
     */
    suspend fun writeFromIdea(
        context: Context,
        idea: String,
        lang: String = "bn"
    ): Result<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val key = getApiKey(context) ?: throw IllegalStateException("NO_API_KEY")
                val langName = if (lang == "en") "English" else "Bengali"
                val prompt = "Write a complete, well-organized note in $langName from this " +
                    "one-line idea. Reply with the note only: the title on the first line, " +
                    "then a blank line, then the note body. No extra commentary, no quotes " +
                    "around the title.\n\nIdea: " + idea.take(500)
                val out = generateText(key, prompt)
                val lines = out.lineSequence().filter { it.isNotBlank() }.toList()
                if (lines.isEmpty()) throw IllegalStateException("Empty reply")
                val title = lines.first().trim().removeSurrounding("\"").take(60)
                val body = out.substringAfter("\n\n", "").trim().ifBlank {
                    lines.drop(1).joinToString("\n").trim()
                }
                if (body.isBlank()) throw IllegalStateException("Empty body")
                title to body
            }
        }

    /**
     * Study helper: writes a school-style answer in [lang] ("bn"/"en")
     * for [topic] in the chosen [typeKey] — PARAGRAPH, LETTER, EMAIL,
     * DIALOGUE, STORY, COMPOSITION, SUMMARY or BHAB (ভাবসম্প্রসারণ).
     * The prompt asks for the Bangladesh NCTB textbook format for that
     * type (e.g. a letter with address/date/salutation/closing, a
     * dialogue with speaker names, a ভাবসম্প্রসারণ in মূলভাব /
     * সম্প্রসারিত ভাব / মন্তব্য parts), at a class 6–10 level.
     * Same result shape as [writeFromIdea]: title + body.
     */
    suspend fun writeStudyMaterial(
        context: Context,
        topic: String,
        typeKey: String,
        lang: String = "bn"
    ): Result<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val key = getApiKey(context) ?: throw IllegalStateException("NO_API_KEY")
                val langName = if (lang == "en") "English" else "Bengali"
                val format = when (typeKey) {
                    "LETTER" -> "a letter in $langName, in Bangladesh school textbook format: " +
                        "sender address, date, salutation, body in 3-4 detailed paragraphs " +
                        "with examples, subscription and name at the end"
                    "EMAIL" -> "a formal email in $langName: To, Subject, greeting, " +
                        "body in 3-4 detailed paragraphs with examples, closing with name"
                    "DIALOGUE" -> "a dialogue in $langName between two people, in Bangladesh " +
                        "school textbook format: speaker names before each line, 12-16 " +
                        "natural exchanges with detail"
                    "STORY" -> "a detailed story in $langName, in Bangladesh school textbook " +
                        "style, about 400-500 words: a clear beginning, middle and end, " +
                        "with details and a moral at the end"
                    "COMPOSITION" -> "a composition in $langName, in Bangladesh school " +
                        "textbook format: introduction, 3-4 detailed body paragraphs with " +
                        "examples, conclusion, about 350-450 words"
                    "SUMMARY" -> "a summary in $langName of the topic below, in Bangladesh " +
                        "school textbook style: only the main points, about one third of " +
                        "the length, in your own words"
                    "BHAB" -> if (lang == "en") {
                        "a detailed expansion of the idea below in English, in the style of a " +
                            "Bangladesh school textbook answer, about 250-350 words: the main idea, an expanded " +
                            "explanation with examples, and a concluding remark"
                    } else {
                        "নিচের ভাবটির ভাবসম্প্রসারণ, বাংলাদেশের পাঠ্যবইয়ের ধরনে, " +
                            "বিস্তারিতভাবে, ২৫০-৩৫০ শব্দের মধ্যে: " +
                            "মূলভাব, সম্প্রসারিত ভাব ও মন্তব্য — এই তিন অংশে, সহজ ভাষায়, উদাহরণসহ"
                    }
                    else -> "a detailed paragraph (200-250 words) in $langName, in the style of a " +
                        "Bangladesh NCTB textbook answer: one clear paragraph, simple sentences, " +
                        "with details and examples"
                }
                val prompt = "Write $format. Reply with the title on the first line, " +
                    "then a blank line, then the body. No extra commentary, no quotes " +
                    "around the title.\n\nTopic: " + topic.take(500)
                val out = generateText(key, prompt)
                val lines = out.lineSequence().filter { it.isNotBlank() }.toList()
                if (lines.isEmpty()) throw IllegalStateException("Empty reply")
                val title = lines.first().trim().removeSurrounding("\"").take(60)
                val body = out.substringAfter("\n\n", "").trim().ifBlank {
                    lines.drop(1).joinToString("\n").trim()
                }
                if (body.isBlank()) throw IllegalStateException("Empty body")
                title to body
            }
        }

    /**
     * Fixes obvious OCR misreads (wrong letters, split or joined words,
     * stray symbols) without changing the wording, capitalization,
     * numbers or line breaks — and keeps any **bold markers** exactly
     * where they are. Returns the input unchanged when it is already
     * clean. Never throws; the caller falls back to the raw OCR text.
     */
    suspend fun cleanOcrText(context: Context, text: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val key = getApiKey(context) ?: throw IllegalStateException("NO_API_KEY")
                val prompt = "Fix obvious OCR misreads in this scanned text (wrong letters, " +
                    "split or joined words, stray symbols). Keep the exact wording, " +
                    "capitalization, numbers and line breaks — do not add, remove or " +
                    "rephrase anything. Keep any **bold** and __underline__ markers " +
                    "exactly where they are. " +
                    "If the text is already clean, return it unchanged. Reply with only " +
                    "the corrected text:\n\n" + text.take(6000)
                generateText(key, prompt)
            }
        }

    /**
     * Vision pass for one scanned page: Gemini reads the photo itself
     * (not just the OCR text), so it can fix what the on-device OCR
     * misread or missed entirely — faded print, shadows, a cut-off
     * edge. It restores punctuation as printed, keeps **bold**,
     * marks __underlined__ text, rebuilds tables as | pipe | tables,
     * and fills an unreadable word from context only when confident,
     * wrapped in [? ?] so the user can verify it against the book.
     * Never throws; the caller keeps the raw OCR text when this fails
     * (no key, no internet, bad reply).
     */
    suspend fun fixScannedPage(context: Context, jpegBytes: ByteArray, ocrText: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val key = getApiKey(context) ?: throw IllegalStateException("NO_API_KEY")
                val prompt = "You are fixing the text of one scanned textbook page. " +
                    "The attached photo IS the page. Below it is machine OCR of the same page, " +
                    "with **bold** markers where the OCR thought the text was bold.\n\n" +
                    "Read the photo carefully and return the page's full text, corrected:\n" +
                    "- Fix misread letters and words. Restore punctuation exactly as printed " +
                    "(full stops, commas, colons, semicolons, dashes, quotes, brackets).\n" +
                    "- Keep the original wording, order, numbers, names, dates and line breaks. " +
                    "Do not add, remove or rephrase anything.\n" +
                    "- Keep **bold** on text that is bold in the photo. " +
                    "Mark text that is underlined in the photo with __double underscores__.\n" +
                    "- Reproduce any table as a Markdown table (| header | ... |), one row per line.\n" +
                    "- If a word is unreadable even in the photo, reconstruct it from context " +
                    "ONLY when you are confident, and wrap ONLY that word in [? ?] " +
                    "(example: [? letter ?]) so the user can check it. Never invent a whole sentence.\n" +
                    "Reply with only the corrected text, nothing else.\n\nOCR text:\n" + ocrText.take(6000)
                generateWithImage(key, prompt, jpegBytes)
            }
        }

    /**
     * Downscales one scanned page to a JPEG byte array (max 1600px)
     * for the vision call, so a huge camera photo does not make a
     * multi-megabyte API request. Null when the page is unreadable.
     */
    fun pageJpegBytes(context: Context, uri: Uri): ByteArray? {
        return try {
            val resolver = context.applicationContext.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 1600 ||
                bounds.outHeight / (sample * 2) >= 1600
            ) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            bitmap.recycle()
            out.toByteArray()
        } catch (e: Exception) {
            null
        }
    }
}
