package com.privatechat.app.notes.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.privatechat.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
    /**
     * The bigger model for hard study questions ("নির্ভুল"): the
     * current stable full Flash. If it fails (e.g. its own daily
     * limit), study calls fall back to [MODEL] automatically.
     */
    private const val ACCURATE_MODEL = "gemini-3.7-flash"
    /**
     * Image generation model (Nano Banana 2.1). Honest limit: image
     * output is NOT on Gemini's free tier — it needs a paid project,
     * so a free key fails here.
     */
    private const val IMAGE_MODEL = "gemini-nano-banana-2.1"
    /** Low temperature for study answers: fewer guesses, more facts. */
    private const val STUDY_TEMPERATURE = 0.3

    /** Maps a Study dialog model key ("FAST"/"ACCURATE") to a model id. */
    fun modelForKey(key: String?): String = if (key == "ACCURATE") ACCURATE_MODEL else MODEL

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
    private suspend fun generateText(
        key: String,
        prompt: String,
        temperature: Double? = null,
        model: String = MODEL,
        timeoutSeconds: Long = 30
    ): String {
        val body = JSONObject()
            .put(
                "contents", JSONArray().put(
                    JSONObject().put(
                        "parts", JSONArray().put(
                            JSONObject().put("text", prompt)
                        )
                    )
                )
            )
        if (temperature != null) {
            body.put("generationConfig", JSONObject().put("temperature", temperature))
        }
        return callGemini(key, body.toString(), timeoutSeconds = timeoutSeconds, model = model)
    }

    /**
     * Same call, but with a JPEG photo attached so Gemini can SEE the
     * page (vision). The model is multimodal, so the same endpoint
     * accepts the image as an inlineData part. Throws on failure.
     */
    private suspend fun generateWithImage(
        key: String,
        prompt: String,
        jpegBytes: ByteArray,
        temperature: Double? = null,
        model: String = MODEL
    ): String {
        val parts = JSONArray()
            .put(JSONObject().put("text", prompt))
            .put(
                JSONObject().put(
                    "inlineData", JSONObject()
                        .put("mimeType", "image/jpeg")
                        .put("data", Base64.encodeToString(jpegBytes, Base64.NO_WRAP))
                )
            )
        val body = JSONObject()
            .put(
                "contents", JSONArray().put(
                    JSONObject().put("parts", parts)
                )
            )
        if (temperature != null) {
            body.put("generationConfig", JSONObject().put("temperature", temperature))
        }
        // Vision calls carry a photo and wait for a long answer, so
        // they get a longer timeout than plain text calls — on a
        // slow connection the default 10 s write timeout killed the
        // upload before it finished, and the scan silently fell
        // back to plain OCR.
        return callGemini(key, body.toString(), timeoutSeconds = 90, model = model)
    }

    /** The HTTP call + reply parsing shared by [generateText] and [generateWithImage]. */
    private suspend fun callGemini(
        key: String,
        bodyJson: String,
        timeoutSeconds: Long = 30,
        model: String = MODEL
    ): String {
        val raw = callGeminiRaw(key, bodyJson, timeoutSeconds, model)
        val out = JSONObject(raw)
            .getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
            .getString("text").trim()
        if (out.isBlank()) throw IllegalStateException("Empty reply")
        return out
    }

    /** One generateContent call returning the raw response body. Throws on HTTP failure. */
    private suspend fun callGeminiRaw(
        key: String,
        bodyJson: String,
        timeoutSeconds: Long = 30,
        model: String = MODEL
    ): String =
        withContext(Dispatchers.IO) {
            val client = OkHttpClient.Builder()
                .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url(
                    "https://generativelanguage.googleapis.com/v1beta/models/" +
                        "$model:generateContent?key=$key"
                )
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                raw
            }
        }

    /**
     * Streaming generateContent (SSE): each text piece is handed to
     * [onChunk] as it arrives. Cancelling the caller's coroutine
     * aborts the network call. Returns the full reply text.
     */
    private suspend fun streamGemini(
        key: String,
        bodyJson: String,
        model: String,
        onChunk: (String) -> Unit
    ): String = suspendCancellableCoroutine { cont ->
        val client = OkHttpClient.Builder()
            .connectTimeout(90, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url(
                "https://generativelanguage.googleapis.com/v1beta/models/" +
                    "$model:streamGenerateContent?alt=sse&key=$key"
            )
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        if (cont.isActive) {
                            cont.resumeWithException(IllegalStateException("HTTP ${it.code}"))
                        }
                        return
                    }
                    val full = StringBuilder()
                    try {
                        val source = it.body?.source()
                            ?: throw IllegalStateException("Empty reply")
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data:")) continue
                            val json = line.removePrefix("data:").trim()
                            if (json.isEmpty()) continue
                            val text = try {
                                JSONObject(json).optJSONArray("candidates")?.optJSONObject(0)
                                    ?.optJSONObject("content")?.optJSONArray("parts")
                                    ?.optJSONObject(0)?.optString("text").orEmpty()
                            } catch (_: Exception) {
                                ""
                            }
                            if (text.isNotEmpty()) {
                                full.append(text)
                                onChunk(text)
                            }
                        }
                        if (cont.isActive) {
                            if (full.isBlank()) {
                                cont.resumeWithException(IllegalStateException("Empty reply"))
                            } else {
                                cont.resume(full.toString())
                            }
                        }
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }
            }
        })
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
     * DIALOGUE, STORY, COMPOSITION, SUMMARY, BHAB (ভাবসম্প্রসারণ),
     * QUESTION, MATH, MCQ, SCIENCE or AUTO (the model picks the format
     * that fits the question). [lengthKey] is SHORT / MEDIUM / LONG;
     * MEDIUM is exactly the old behaviour (no length note in the
     * prompt). The prompt asks for the Bangladesh NCTB textbook format
     * for that type (e.g. a letter with address/date/salutation/
     * closing, a dialogue with speaker names, a ভাবসম্প্রসারণ in
     * মূলভাব / সম্প্রসারিত ভাব / মন্তব্য parts), at a class 6–10 level.
     * Same result shape as [writeFromIdea]: title + body.
     */
    suspend fun writeStudyMaterial(
        context: Context,
        topic: String,
        typeKey: String,
        lang: String = "bn",
        lengthKey: String = "MEDIUM",
        extraNote: String = "",
        model: String = MODEL
    ): Result<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val key = getApiKey(context) ?: throw IllegalStateException("NO_API_KEY")
                val format = studyFormat(typeKey, lang)
                val prompt = "Write $format." + studySuffix(typeKey, lengthKey, extraNote) +
                    " Reply with the title on the first line, " +
                    "then a blank line, then the body. No extra commentary, no quotes " +
                    "around the title.\n\nTopic: " + topic.take(500)
                val first = splitStudyReply(generateForStudy(key, prompt, model, null))
                verifyStudyAnswer(key, topic, first, model).getOrNull()?.let { verified ->
                    runCatching { splitStudyReply(verified) }.getOrNull()
                } ?: first
            }
        }

    /**
     * One study call at low temperature, with the chosen model; if
     * the accurate model fails (e.g. its own daily limit), it falls
     * back to the fast model once instead of failing the answer.
     */
    private suspend fun generateForStudy(
        key: String,
        prompt: String,
        model: String,
        jpegBytes: ByteArray?
    ): String {
        val attempt = runCatching {
            if (jpegBytes != null) {
                generateWithImage(key, prompt, jpegBytes, temperature = STUDY_TEMPERATURE, model = model)
            } else {
                generateText(key, prompt, temperature = STUDY_TEMPERATURE, model = model)
            }
        }
        if (attempt.isSuccess || model == MODEL) return attempt.getOrThrow()
        return if (jpegBytes != null) {
            generateWithImage(key, prompt, jpegBytes, temperature = STUDY_TEMPERATURE, model = MODEL)
        } else {
            generateText(key, prompt, temperature = STUDY_TEMPERATURE, model = MODEL)
        }
    }

    /**
     * Second pass: re-checks a study answer against its question and
     * fixes mistakes (calculation errors, wrong facts, wrong options,
     * missed parts). Returns the corrected reply text in the same
     * title+body shape, or failure — the caller then keeps the first
     * answer, so a failed check never loses a good answer.
     */
    private suspend fun verifyStudyAnswer(
        key: String,
        topic: String,
        first: Pair<String, String>,
        model: String
    ): Result<String> =
        runCatching {
            val prompt = "You are checking a study answer for mistakes. Question: " +
                topic.take(500) + "\n\nAnswer title: " + first.first +
                "\nAnswer:\n" + first.second.take(4000) +
                "\n\nCheck it carefully against the question: fix any calculation errors, " +
                "wrong facts, wrong options, or missed parts. Only fix mistakes — do not " +
                "rewrite correct parts or change the style or length. If you are not sure " +
                "about a fact or a step, mark that line with ⚠️. Reply with the corrected " +
                "answer in the same format: the title on the first line, then a blank line, " +
                "then the body. No extra commentary."
            generateForStudy(key, prompt, model, null)
        }

    /**
     * Answers each question in its own call (same type/language/length/
     * note/model), then combines them into one note: the first answer's
     * title, and one bold question line + answer per question. A question
     * that fails gets a short "could not answer" line instead, so one
     * failure never loses the other answers.
     */
    suspend fun answerQuestionsOneByOne(
        context: Context,
        questions: List<String>,
        typeKey: String,
        lang: String = "bn",
        lengthKey: String = "MEDIUM",
        extraNote: String = "",
        model: String = MODEL
    ): Result<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                var title: String? = null
                val sections = StringBuilder()
                for (q in questions) {
                    val one = writeStudyMaterial(context, q, typeKey, lang, lengthKey, extraNote, model)
                    if (one.isSuccess) {
                        val (t, b) = one.getOrThrow()
                        if (title == null) title = t
                        sections.append("**").append(q.trim().take(200)).append("**\n")
                            .append(b).append("\n\n")
                    } else {
                        sections.append("**").append(q.trim().take(200)).append("**\n")
                            .append(context.getString(R.string.notes_study_question_failed))
                            .append("\n\n")
                    }
                }
                val finalTitle = title ?: throw IllegalStateException("Empty reply")
                finalTitle to sections.toString().trim()
            }
        }

    /** One turn of an AI chat: role is "user" or "assistant". */
    data class ChatTurn(val role: String, val text: String)

    /**
     * AI chat (ChatGPT-style): answers a new message with the recent
     * conversation as context, so follow-ups ("আরও সহজ করে বলো") work.
     * Attaches the photo when the new message has one, so the user
     * can ask anything about an image. Only the current message's
     * photo is sent; older turns keep a "[Image attached]" note in
     * the transcript. When [noteContext] is given (a note the user is
     * asking about), it travels as context, not as a visible message.
     * Returns the assistant's reply text.
     */
    suspend fun chatAnswer(
        context: Context,
        history: List<ChatTurn>,
        message: String,
        jpegBytes: ByteArray? = null,
        model: String = MODEL,
        noteContext: String? = null
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val key = getApiKey(context) ?: throw IllegalStateException("NO_API_KEY")
                val prompt = buildChatPrompt(history, message, jpegBytes != null, noteContext)
                val out = if (jpegBytes != null) {
                    generateWithImage(key, prompt, jpegBytes, temperature = 0.7, model = model)
                } else {
                    generateText(key, prompt, temperature = 0.7, model = model, timeoutSeconds = 60)
                }
                val reply = out.trim()
                if (reply.isBlank()) throw IllegalStateException("Empty reply")
                reply
            }
        }

    /**
     * Streaming AI chat: the same answer as [chatAnswer], but each
     * piece of text is handed to [onChunk] as it arrives, so the
     * reply appears word by word instead of all at once.
     */
    suspend fun chatAnswerStream(
        context: Context,
        history: List<ChatTurn>,
        message: String,
        jpegBytes: ByteArray? = null,
        model: String = MODEL,
        noteContext: String? = null,
        onChunk: (String) -> Unit
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val key = getApiKey(context) ?: throw IllegalStateException("NO_API_KEY")
                val prompt = buildChatPrompt(history, message, jpegBytes != null, noteContext)
                val parts = JSONArray()
                    .put(JSONObject().put("text", prompt))
                if (jpegBytes != null) {
                    parts.put(
                        JSONObject().put(
                            "inlineData", JSONObject()
                                .put("mimeType", "image/jpeg")
                                .put("data", Base64.encodeToString(jpegBytes, Base64.NO_WRAP))
                        )
                    )
                }
                val body = JSONObject()
                    .put(
                        "contents", JSONArray().put(
                            JSONObject().put("parts", parts)
                        )
                    )
                    .put("generationConfig", JSONObject().put("temperature", 0.7))
                streamGemini(key, body.toString(), model, onChunk)
            }
        }

    /** The shared chat prompt: instructions + note context + recent turns + new message. */
    private fun buildChatPrompt(
        history: List<ChatTurn>,
        message: String,
        hasImage: Boolean,
        noteContext: String?
    ): String {
        val transcript = StringBuilder()
        for (t in history.takeLast(10)) {
            transcript.append(if (t.role == "user") "User: " else "Assistant: ")
                .append(t.text.take(1500)).append("\n\n")
        }
        val notePart = if (!noteContext.isNullOrBlank()) {
            "The user is asking about this note — use it as context, and say so if a " +
                "question has nothing to do with it:\n" + noteContext.take(4000) + "\n\n"
        } else ""
        return "You are a helpful AI assistant, like ChatGPT or Gemini. " +
            "Answer professionally and clearly, in the same language the user writes in " +
            "(Bengali if they write Bengali, English if they write English). " +
            "Use **bold** for key terms, short paragraphs, and numbered steps or dash " +
            "bullets when they help. If you are not sure about a fact, say so honestly " +
            "instead of guessing. Write math in plain readable text, never LaTeX.\n\n" +
            notePart +
            "Conversation so far:\n" + transcript.toString() +
            "User: " + message.take(2000) +
            (if (hasImage) "\n(The user attached a photo with this message — look at it and answer about what it shows.)" else "") +
            "\nAssistant:"
    }

    /**
     * Generates one image from a text description (Nano Banana 2.1).
     * Returns the PNG bytes. Honest limit: image output is NOT on
     * Gemini's free tier — it needs a paid project, so a free key
     * fails here (HTTP 403 / quota 0).
     */
    suspend fun generateImageBytes(context: Context, prompt: String): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            runCatching {
                val key = getApiKey(context) ?: throw IllegalStateException("NO_API_KEY")
                val body = JSONObject()
                    .put(
                        "contents", JSONArray().put(
                            JSONObject().put(
                                "parts", JSONArray().put(
                                    JSONObject().put("text", prompt)
                                )
                            )
                        )
                    )
                    .put(
                        "generationConfig", JSONObject()
                            .put("responseModalities", JSONArray().put("IMAGE"))
                            .put("imageConfig", JSONObject().put("imageSize", "1K"))
                    )
                val raw = callGeminiRaw(key, body.toString(), timeoutSeconds = 90, model = IMAGE_MODEL)
                val parts = JSONObject(raw)
                    .getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                for (i in 0 until parts.length()) {
                    val data = parts.optJSONObject(i)
                        ?.optJSONObject("inlineData")?.optString("data").orEmpty()
                    if (data.isNotBlank()) return@runCatching Base64.decode(data, Base64.DEFAULT)
                }
                throw IllegalStateException("NO_IMAGE")
            }
        }

    /**
     * Extra instructions appended to a study prompt: answer length,
     * plain-text math (never LaTeX — the app cannot render it, so
     * $...$ and \frac would show as unreadable markup), and the
     * user's additional note, when they wrote one.
     */
    private fun studySuffix(typeKey: String, lengthKey: String, extraNote: String): String {
        val lengthNote = when (lengthKey) {
            "SHORT" -> " Keep it short and to the point, about half the usual length " +
                "for this kind of answer, without dropping the key points."
            "LONG" -> " Give a detailed answer, about twice the usual length for this " +
                "kind of answer, with extra examples and explanation."
            else -> ""
        }
        val mathNote = if (typeKey == "MATH" || typeKey == "SCIENCE" || typeKey == "AUTO") {
            " Write math in plain readable text, never LaTeX: use √ for square root, " +
                "/ for fractions, ^ for powers and dy/dx for derivatives — never $...$, " +
                "\\frac, \\sqrt or any other LaTeX command."
        } else ""
        val extra = if (extraNote.isNotBlank()) {
            " The user added this note about the question: " + extraNote.take(300) +
                " Follow it — for example, if they say only certain questions or a " +
                "certain part of the photo, answer only those."
        } else ""
        val unsure = " If you are not sure about a fact, a step or an answer, mark that " +
            "line with ⚠️ so the user can check it against the book."
        return lengthNote + mathNote + extra + unsure
    }

    /** The answer format for a study type, in the requested language. */
    private fun studyFormat(typeKey: String, lang: String): String {
        val langName = if (lang == "en") "English" else "Bengali"
        return when (typeKey) {
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
            "QUESTION" -> "an answer to the question below in $langName, in the style " +
                "of a Bangladesh school textbook answer: a clear direct answer first, " +
                "then a simple explanation in easy language with an example when useful. " +
                "If the question has lettered or numbered parts (a, b, c…), answer every " +
                "part on its own line in order, like (a) answer — one short explanation beside it"
            "MATH" -> "a step-by-step solution to the math problem below in $langName, " +
                "exactly like a Bangladesh school textbook solution: each step on its " +
                "own line with the working shown, then the final answer, then a short " +
                "explanation in easy language of why each step is done. Follow this worked " +
                "example's style, not its problem: Question: Solve 2x + 6 = 14. " +
                "Step 1: 2x = 14 − 6 = 8. Step 2: x = 8 / 2 = 4. Answer: x = 4. " +
                "Explanation: first move 6 to the other side, then divide by 2"
            "MCQ" -> "an answer to the multiple-choice question below in $langName, " +
                "in Bangladesh school textbook style: first the correct option " +
                "(its letter and text), then why it is correct, then briefly why " +
                "each other option is wrong"
            "SCIENCE" -> "a step-by-step solution to the science (physics/chemistry) " +
                "numerical problem below in $langName, exactly like a Bangladesh school " +
                "textbook solution: the formula first, then the values substituted, " +
                "then the calculation step by step, then the final answer with the " +
                "correct unit, then a short explanation in easy language"
            "AUTO" -> "an answer to the question below in $langName, in the format " +
                "that fits it best: a step-by-step math solution with the working shown " +
                "if it is a math problem; a formula, substituted values and step-by-step " +
                "calculation with the unit if it is a science numerical problem; the " +
                "correct option plus why it is correct and why the others are wrong " +
                "if it is a multiple-choice question; a letter, email or dialogue in " +
                "Bangladesh school textbook format if it asks for one; an expansion " +
                "in মূলভাব / সম্প্রসারিত ভাব / মন্তব্য parts if it is an idea to " +
                "expand; otherwise a clear textbook-style answer with a simple " +
                "explanation and an example. If there are several questions, answer " +
                "each one in turn, repeating each question before its answer. If a " +
                "question has lettered or numbered parts (a, b, c…), answer every part " +
                "on its own line in order, like (a) answer — one short explanation beside it"
            else -> "a detailed paragraph (200-250 words) in $langName, in the style of a " +
                "Bangladesh NCTB textbook answer: one clear paragraph, simple sentences, " +
                "with details and examples"
        }
    }

    /** Splits a study reply into title + body (title = first line). */
    private fun splitStudyReply(out: String): Pair<String, String> {
        val lines = out.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) throw IllegalStateException("Empty reply")
        val title = lines.first().trim().removeSurrounding("\"").take(60)
        val body = out.substringAfter("\n\n", "").trim().ifBlank {
            lines.drop(1).joinToString("\n").trim()
        }
        if (body.isBlank()) throw IllegalStateException("Empty body")
        return title to body
    }

    /**
     * Study answer WITH the photo: for questions that come with a
     * diagram, graph or table, the text alone is not enough — the
     * model sees the photo too, so the diagram is part of the answer.
     * Same formats and length as [writeStudyMaterial].
     */
    suspend fun writeStudyMaterialWithImage(
        context: Context,
        topic: String,
        typeKey: String,
        lang: String = "bn",
        lengthKey: String = "MEDIUM",
        extraNote: String = "",
        jpegBytes: ByteArray,
        model: String = MODEL
    ): Result<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val key = getApiKey(context) ?: throw IllegalStateException("NO_API_KEY")
                val format = studyFormat(typeKey, lang)
                val prompt = "Write $format." + studySuffix(typeKey, lengthKey, extraNote) +
                    " Reply with the title on the first line, " +
                    "then a blank line, then the body. No extra commentary, no quotes " +
                    "around the title.\n\nTopic: " + topic.take(500) +
                    "\nThe attached photo shows the question — use anything in it, " +
                    "including a diagram, graph or table, to answer."
                val first = splitStudyReply(generateForStudy(key, prompt, model, jpegBytes))
                verifyStudyAnswer(key, topic, first, model).getOrNull()?.let { verified ->
                    runCatching { splitStudyReply(verified) }.getOrNull()
                } ?: first
            }
        }

    /**
     * Study helper "Scan question": reads EVERY question out of a
     * photo (Bengali, English or math — numbers, symbols and formulas
     * included), one per line, in order. Tried twice, like the scan
     * vision fix — the first call often fails on a flaky connection.
     * Never answers the questions itself; the answers come from
     * [writeStudyMaterial]. Empty list when nothing readable is found.
     */
    suspend fun readQuestionsFromImage(context: Context, jpegBytes: ByteArray): Result<List<String>> =
        withContext(Dispatchers.IO) {
            var lastError: Throwable? = null
            repeat(2) {
                val attempt = runCatching {
                    val key = getApiKey(context) ?: throw IllegalStateException("NO_API_KEY")
                    val prompt = "List every separate question visible in this photo, in order, " +
                        "one per line, exactly as printed, including numbers, math symbols and " +
                        "formulas. Do not number them, do not answer them, do not add any other words."
                    val out = generateWithImage(
                        key, prompt, jpegBytes, temperature = STUDY_TEMPERATURE
                    ).trim()
                    if (out.isBlank()) throw IllegalStateException("Empty reply")
                    out.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .map {
                            it.replace(Regex("^\\d+[.)]\\s*"), "")
                                .replace(Regex("^Q\\d+[:.)]\\s*"), "").trim()
                        }
                        .filter { it.isNotBlank() }
                        .toList()
                        .also { if (it.isEmpty()) throw IllegalStateException("Empty reply") }
                }
                if (attempt.isSuccess) return@withContext attempt
                lastError = attempt.exceptionOrNull()
            }
            Result.failure(lastError ?: IllegalStateException("Empty reply"))
        }

    /**
     * @ai in a note: answers the user's question like a tutor, in the
     * question's own language (Bengali or English), using the rest of
     * the note as context when it is relevant. Textbook style when it
     * fits — a direct answer first, then a simple explanation.
     */
    suspend fun answerNoteQuestion(
        context: Context,
        question: String,
        noteContext: String
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val key = getApiKey(context) ?: throw IllegalStateException("NO_API_KEY")
                val prompt = "You are a helpful tutor like ChatGPT. The user wrote a note " +
                    "(context below) and asked a question marked @ai. Answer it professionally " +
                    "and clearly, in the same language as the question (Bengali if the question " +
                    "is in Bengali, English if it is in English), in a Bangladesh school " +
                    "textbook style when it fits. Write it like a professional Gemini answer: " +
                    "a direct answer first, then short clear sections with a bold section line " +
                    "when the answer has parts; use **bold** for key terms; numbered steps for " +
                    "procedures; dash (-) bullets for lists; a short example when it helps. " +
                    "Write math in plain readable text, never LaTeX: use √ for square root, " +
                    "/ for fractions, ^ for powers and dy/dx for derivatives — never $...$, " +
                    "\\frac, \\sqrt or any other LaTeX command. " +
                    "If you are not sure about a fact, a step or an answer, mark that " +
                    "line with ⚠️ so the user can check it against the book. " +
                    "If the question has lettered or numbered parts (a, b, c…), answer every " +
                    "part on its own line in order, like (a) answer — one short explanation beside it. " +
                    "If it is a multiple-choice question, give the correct option and explain " +
                    "why the others are wrong. Use the note's context when it is relevant. " +
                    "Reply with only the answer, no extra commentary.\n\n" +
                    "Note context:\n" + noteContext.take(4000) + "\n\nQuestion: " + question.take(1000)
                val out = generateText(key, prompt, temperature = STUDY_TEMPERATURE).trim()
                if (out.isBlank()) throw IllegalStateException("Empty reply")
                out
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
                // The whole OCR text is the prompt's safety net: a dense
                // textbook page can run past 6000 chars, and cutting it
                // cut the sections after a table out of the model's view.
                val ocr = ocrText.take(12000)
                val prompt = "You are fixing the text of one scanned textbook page. " +
                    "The attached photo IS the page. Below it is machine OCR of the same page, " +
                    "with **bold** markers where the OCR thought the text was bold.\n\n" +
                    "Read the photo carefully and return the page's full text, corrected:\n" +
                    "- Fix misread letters and words. Restore punctuation exactly as printed " +
                    "(full stops, commas, colons, semicolons, dashes, quotes, brackets).\n" +
                    "- Keep the original wording, order, numbers, names, dates and line breaks. " +
                    "Do not add, remove or rephrase anything. Return the WHOLE page from top " +
                    "to bottom, including every section after a table (for example a specimen " +
                    "letter below the table). Never stop after a table.\n" +
                    "- Keep **bold** on text that is bold in the photo. " +
                    "Mark text that is underlined in the photo with __double underscores__.\n" +
                    "- Reproduce any table as a Markdown table (| header | ... |), one row per line. " +
                    "Inside a table cell, never use <br> or any HTML tag — write the cell text " +
                    "as one flowing line; it will wrap on its own.\n" +
                    "- Write math in plain readable text, never LaTeX: use √ for square root, " +
                    "/ for fractions, ^ for powers, dy/dx for derivatives — never $...$, " +
                    "\\frac, \\sqrt or any other LaTeX command.\n" +
                    "- If a word is unreadable even in the photo, reconstruct it from context " +
                    "ONLY when you are confident, and wrap ONLY that word in [? ?] " +
                    "(example: [? letter ?]) so the user can check it. Never invent a whole sentence.\n" +
                    "Reply with only the corrected text, nothing else.\n\nOCR text:\n" + ocr
                val fixed = generateWithImage(
                    key, prompt, jpegBytes, temperature = STUDY_TEMPERATURE
                )
                // Safety net: the vision pass fixes wording, it does not
                // delete whole sections — if its reply is much shorter
                // than the OCR text, it dropped part of the page (seen:
                // a specimen letter below a table vanished). Fall back
                // to the OCR text then; a plain scan beats a silently
                // incomplete one.
                if (fixed.length < ocr.length * 0.75) ocr else fixed
            }
        }

    /**
     * Vision fix with one retry — the first call often fails on a
     * flaky connection, and a second attempt costs nothing when the
     * first one worked. Returns the Result of the last attempt.
     */
    suspend fun fixScannedPageWithRetry(
        context: Context,
        jpegBytes: ByteArray,
        ocrText: String
    ): Result<String> {
        val first = fixScannedPage(context, jpegBytes, ocrText)
        if (first.isSuccess) return first
        return fixScannedPage(context, jpegBytes, ocrText)
    }

    /** Resource id of the short user-facing reason for a failed vision call. */
    fun visionFailureReasonRes(e: Throwable?): Int = when (e) {
        is java.net.UnknownHostException -> R.string.notes_scan_ai_reason_no_internet
        is java.net.SocketTimeoutException -> R.string.notes_scan_ai_reason_timeout
        is java.io.IOException -> R.string.notes_scan_ai_reason_no_internet
        is IllegalStateException -> when (e.message) {
            "NO_API_KEY" -> R.string.notes_scan_ai_reason_no_key
            "Empty reply" -> R.string.notes_scan_ai_reason_empty
            else -> {
                val code = e.message?.removePrefix("HTTP ")?.toIntOrNull()
                when (code) {
                    401, 403 -> R.string.notes_scan_ai_reason_key
                    429 -> R.string.notes_scan_ai_reason_quota
                    in 500..599 -> R.string.notes_scan_ai_reason_server
                    else -> R.string.notes_scan_ai_reason_other
                }
            }
        }
        else -> R.string.notes_scan_ai_reason_other
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
