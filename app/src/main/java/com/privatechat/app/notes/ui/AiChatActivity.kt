package com.privatechat.app.notes.ui

import android.content.ContentValues
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.privatechat.app.R
import com.privatechat.app.notes.data.AiChat
import com.privatechat.app.notes.data.AiChatMessageEntity
import com.privatechat.app.notes.data.NotesDatabase
import com.privatechat.app.notes.util.GeminiSummarizer
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * AI chat (ChatGPT-style): a conversation with Gemini. The AI
 * remembers the recent turns, answers word by word as the reply
 * streams in, and can look at an attached photo and answer about it.
 * Conversations are saved on this device (see [AiChatListActivity])
 * and can be continued later, exported as text, or saved as a note.
 */
class AiChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_NOTE_CONTEXT = "note_context"
        const val EXTRA_NOTE_TITLE = "note_title"
        private const val PREFS = "ai_chat"
        private const val KEY_MODEL = "model"
    }

    private lateinit var toolbar: Toolbar
    private lateinit var adapter: AiChatAdapter
    private lateinit var list: RecyclerView
    private lateinit var input: EditText
    private lateinit var previewRow: LinearLayout
    private lateinit var previewImage: ImageView
    private lateinit var sendButton: ImageButton
    private lateinit var chipsRow: LinearLayout
    private val messages = mutableListOf<AiChatMessage>()
    private val history = mutableListOf<GeminiSummarizer.ChatTurn>()
    private var pendingImage: ByteArray? = null
    private var answering = false
    private var answerJob: Job? = null
    private var chatId: Long = -1
    private var noteContext: String? = null
    private var modelKey: String = "FAST"
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speakingIndex: Int? = null
    private val dao by lazy { NotesDatabase.get(this).aiChatDao() }
    /** Serializes chat-row creation, message saves and deletes so they never race. */
    private val dbMutex = Mutex()

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            val bytes = GeminiSummarizer.pageJpegBytes(this, uri)
            if (bytes == null) {
                Toast.makeText(
                    this, getString(R.string.notes_ai_chat_image_failed), Toast.LENGTH_SHORT
                ).show()
                return@registerForActivityResult
            }
            pendingImage = bytes
            previewImage.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            previewRow.visibility = View.VISIBLE
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                val done = withContext(Dispatchers.IO) {
                    runCatching {
                        contentResolver.openOutputStream(uri)?.use {
                            it.write(buildTranscript().toByteArray())
                        } ?: throw IllegalStateException("Could not open file")
                    }.isSuccess
                }
                Toast.makeText(
                    this@AiChatActivity,
                    getString(if (done) R.string.notes_ai_chat_exported else R.string.notes_ai_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    private val speakLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                input.setText(spoken)
                input.setSelection(spoken.length)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_chat)
        chatId = intent.getLongExtra(EXTRA_CHAT_ID, -1)
        noteContext = intent.getStringExtra(EXTRA_NOTE_CONTEXT)?.takeIf { it.isNotBlank() }
        val noteTitle = intent.getStringExtra(EXTRA_NOTE_TITLE)?.takeIf { it.isNotBlank() }
        modelKey = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_MODEL, "FAST") ?: "FAST"

        toolbar = findViewById(R.id.aiChatToolbar)
        toolbar.title = noteTitle?.take(30) ?: getString(R.string.notes_ai_chat)
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = AiChatAdapter(
            messages,
            onRegenerate = { index -> regenerate(index) },
            onListen = { index -> listen(index) },
            onImageLongPress = { bytes -> saveImageToGallery(bytes) }
        )
        list = findViewById(R.id.aiChatList)
        list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        list.adapter = adapter

        input = findViewById(R.id.aiChatInput)
        previewRow = findViewById(R.id.aiChatImagePreviewRow)
        previewImage = findViewById(R.id.aiChatImagePreview)
        chipsRow = findViewById(R.id.aiChatChipsRow)
        findViewById<Button>(R.id.aiChatChip1).setOnClickListener {
            sendMessage(getString(R.string.notes_ai_chat_chip_fact))
        }
        findViewById<Button>(R.id.aiChatChip2).setOnClickListener {
            sendMessage(getString(R.string.notes_ai_chat_chip_quote))
        }
        findViewById<Button>(R.id.aiChatChip3).setOnClickListener {
            sendMessage(getString(R.string.notes_ai_chat_chip_story))
        }
        findViewById<Button>(R.id.aiChatChip4).setOnClickListener {
            sendMessage(getString(R.string.notes_ai_chat_chip_quiz))
        }
        findViewById<ImageButton>(R.id.aiChatImageRemove).setOnClickListener {
            pendingImage = null
            previewRow.visibility = View.GONE
        }
        findViewById<ImageButton>(R.id.aiChatAttach).setOnClickListener {
            pickImageLauncher.launch("image/*")
        }
        findViewById<ImageButton>(R.id.aiChatMic).setOnClickListener { startVoiceInput() }
        sendButton = findViewById(R.id.aiChatSend)
        sendButton.setOnClickListener {
            if (answering) answerJob?.cancel() else sendMessage()
        }
        if (chatId != -1L) loadChat(chatId) else updateChipsVisibility()
    }

    override fun onDestroy() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_ai_chat, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_model_fast)?.isChecked = modelKey == "FAST"
        menu.findItem(R.id.action_model_accurate)?.isChecked = modelKey == "ACCURATE"
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_ai_save_note -> {
                saveChatAsNote()
                true
            }
            R.id.action_ai_export -> {
                exportLauncher.launch("ai-chat.txt")
                true
            }
            R.id.action_ai_create_image -> {
                askCreateImage()
                true
            }
            R.id.action_ai_new_chat -> {
                askNewChat()
                true
            }
            R.id.action_model_fast -> {
                setModel("FAST")
                true
            }
            R.id.action_model_accurate -> {
                setModel("ACCURATE")
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setModel(key: String) {
        modelKey = key
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_MODEL, key).apply()
        invalidateOptionsMenu()
    }

    // ---- Chat loading / saving (Room) ----

    private fun loadChat(id: Long) {
        lifecycleScope.launch {
            val chat = withContext(Dispatchers.IO) { dao.getChat(id) } ?: return@launch
            val stored = withContext(Dispatchers.IO) { dao.getMessages(id) }
            noteContext = chat.contextText.takeIf { it.isNotBlank() }
            toolbar.title = chat.title.take(30)
            for (m in stored) {
                messages.add(
                    AiChatMessage(text = m.text, isUser = m.role == "user", image = m.image)
                )
                history.add(
                    GeminiSummarizer.ChatTurn(
                        m.role,
                        m.text.ifBlank { "[Image]" } +
                            if (m.image != null && m.text.isNotBlank()) " [Image attached]" else ""
                    )
                )
            }
            adapter.notifyDataSetChanged()
            updateChipsVisibility()
            scrollToBottom()
        }
    }

    /** Inserts one message and touches the chat row; creates the chat row on the first message. */
    private fun saveMessage(role: String, text: String, image: ByteArray?) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                dbMutex.withLock {
                    if (chatId == -1L) {
                        val title = messages.firstOrNull { it.isUser }?.text.orEmpty()
                            .take(40).ifBlank { getString(R.string.notes_ai_chat) }
                        chatId = dao.insertChat(
                            AiChat(
                                title = title,
                                contextText = noteContext.orEmpty(),
                                createdAt = System.currentTimeMillis(),
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                    dao.insertMessage(
                        AiChatMessageEntity(
                            chatId = chatId,
                            role = role,
                            text = text,
                            image = image,
                            createdAt = System.currentTimeMillis()
                        )
                    )
                    dao.touchChat(chatId, System.currentTimeMillis())
                }
            }
        }
    }

    /** Deletes the newest saved assistant message (used when an answer is regenerated). */
    private fun deleteLastAssistantMessage() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                dbMutex.withLock {
                    if (chatId != -1L) dao.deleteLastAssistantMessage(chatId)
                }
            }
        }
    }

    // ---- Sending / streaming / stopping ----

    private fun sendMessage(textOverride: String? = null) {
        if (answering) return
        val text = (textOverride ?: input.text.toString()).trim()
        val image = pendingImage
        if (text.isBlank() && image == null) return
        // A photo with no question gets a default "what is in it" ask.
        val question = if (text.isNotBlank()) text else getString(R.string.notes_ai_chat_ask_image)
        addUserMessage(question, image)
        input.text.clear()
        pendingImage = null
        previewRow.visibility = View.GONE
        runAnswer(question, image)
    }

    /** Adds a message and hides the Regenerate button of the previous AI answer. */
    private fun addMessage(msg: AiChatMessage): Int {
        val prevLastAi = lastFinishedAiIndex()
        messages.add(msg)
        val index = messages.size - 1
        adapter.notifyItemInserted(index)
        if (prevLastAi >= 0 && prevLastAi != index) adapter.notifyItemChanged(prevLastAi)
        scrollToBottom()
        updateChipsVisibility()
        return index
    }

    private fun lastFinishedAiIndex(): Int {
        for (i in messages.size - 1 downTo 0) {
            val m = messages[i]
            if (!m.isUser && !m.isTyping && !m.isLive) return i
        }
        return -1
    }

    private fun addUserMessage(question: String, image: ByteArray?) {
        addMessage(AiChatMessage(text = question, isUser = true, image = image))
        history.add(
            GeminiSummarizer.ChatTurn(
                "user",
                question + if (image != null) " [Image attached]" else ""
            )
        )
        saveMessage("user", question, image)
    }

    private fun runAnswer(question: String, image: ByteArray?) {
        val liveIndex = addMessage(AiChatMessage(text = "", isUser = false, isLive = true))
        answering = true
        updateSendButton()
        answerJob = lifecycleScope.launch {
            var recorded = false
            try {
                val model = GeminiSummarizer.modelForKey(modelKey)
                val result = GeminiSummarizer.chatAnswerStream(
                    this@AiChatActivity, history.toList(), question, image, model, noteContext
                ) { chunk ->
                    runOnUiThread {
                        val current = messages.getOrNull(liveIndex) ?: return@runOnUiThread
                        if (!current.isLive) return@runOnUiThread
                        messages[liveIndex] = current.copy(text = current.text + chunk)
                        adapter.notifyItemChanged(liveIndex)
                        scrollToBottom()
                    }
                }
                if (result.isSuccess) {
                    val reply = result.getOrThrow()
                    finishLiveMessage(liveIndex)
                    history.add(GeminiSummarizer.ChatTurn("assistant", reply))
                    saveMessage("assistant", reply, null)
                    recorded = true
                } else {
                    val partial = messages.getOrNull(liveIndex)?.text.orEmpty()
                    if (partial.isNotBlank()) {
                        // The stream broke mid-answer: keep what arrived.
                        finishLiveMessage(liveIndex)
                        history.add(GeminiSummarizer.ChatTurn("assistant", partial))
                        saveMessage("assistant", partial, null)
                        recorded = true
                    } else {
                        // Streaming gave nothing — fall back to the one-shot call.
                        recorded = true
                        messages.removeAt(liveIndex)
                        adapter.notifyItemRemoved(liveIndex)
                        runAnswerFallback(question, image)
                    }
                }
            } finally {
                if (!recorded) {
                    // Cancelled (Stop) before the answer was recorded: keep
                    // whatever arrived as the answer, or drop the empty bubble.
                    val live = messages.getOrNull(liveIndex)
                    if (live != null && live.text.isNotBlank()) {
                        finishLiveMessage(liveIndex)
                        history.add(GeminiSummarizer.ChatTurn("assistant", live.text))
                        saveMessage("assistant", live.text, null)
                    } else if (live != null) {
                        messages.removeAt(liveIndex)
                        adapter.notifyItemRemoved(liveIndex)
                    }
                }
                answering = false
                updateSendButton()
            }
        }
    }

    /** One-shot answer, used only when streaming returned nothing. Never throws. */
    private suspend fun runAnswerFallback(question: String, image: ByteArray?) {
        val liveIndex = addMessage(AiChatMessage(text = "", isUser = false, isLive = true))
        try {
            val model = GeminiSummarizer.modelForKey(modelKey)
            val result = GeminiSummarizer.chatAnswer(
                this@AiChatActivity, history.toList(), question, image, model, noteContext
            )
            result.onSuccess { reply ->
                messages[liveIndex] = AiChatMessage(text = reply, isUser = false)
                adapter.notifyItemChanged(liveIndex)
                history.add(GeminiSummarizer.ChatTurn("assistant", reply))
                saveMessage("assistant", reply, null)
            }.onFailure {
                messages.removeAt(liveIndex)
                adapter.notifyItemRemoved(liveIndex)
                Toast.makeText(
                    this@AiChatActivity, getString(R.string.notes_ai_failed), Toast.LENGTH_SHORT
                ).show()
            }
        } finally {
            // Cancelled mid-fallback: drop the empty bubble (a finished
            // answer is no longer live, so this only removes the empty one).
            val live = messages.getOrNull(liveIndex)
            if (live != null && live.isLive) {
                messages.removeAt(liveIndex)
                adapter.notifyItemRemoved(liveIndex)
            }
        }
    }

    private fun finishLiveMessage(index: Int) {
        val current = messages.getOrNull(index) ?: return
        if (!current.isLive) return
        messages[index] = current.copy(isLive = false)
        adapter.notifyItemChanged(index)
    }

    private fun updateSendButton() {
        sendButton.setImageResource(
            if (answering) android.R.drawable.ic_media_pause else R.drawable.ic_send
        )
    }

    private fun scrollToBottom() {
        if (messages.isNotEmpty()) list.scrollToPosition(messages.size - 1)
    }

    private fun updateChipsVisibility() {
        chipsRow.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
    }

    // ---- Regenerate / listen ----

    private fun regenerate(index: Int) {
        if (answering) return
        val aiMsg = messages.getOrNull(index) ?: return
        if (aiMsg.isUser || aiMsg.isTyping || aiMsg.isLive) return
        // The question is the last user message before this answer.
        var question: String? = null
        var image: ByteArray? = null
        for (i in index - 1 downTo 0) {
            val m = messages[i]
            if (m.isUser) {
                question = m.text
                image = m.image
                break
            }
        }
        if (question == null) return
        // Drop the old answer (message + history turn + saved row) and answer again.
        messages.removeAt(index)
        adapter.notifyItemRemoved(index)
        if (history.isNotEmpty() && history.last().role == "assistant") {
            history.removeAt(history.size - 1)
        }
        deleteLastAssistantMessage()
        runAnswer(question, image)
    }

    private fun listen(index: Int) {
        val msg = messages.getOrNull(index) ?: return
        if (msg.isUser || msg.isTyping || msg.isLive || msg.text.isBlank()) return
        if (speakingIndex == index) {
            stopSpeaking()
            return
        }
        stopSpeaking()
        val text = msg.text
        if (tts == null) {
            tts = TextToSpeech(this) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) startSpeaking(index, text)
                else Toast.makeText(
                    this, getString(R.string.notes_read_no_tts), Toast.LENGTH_SHORT
                ).show()
            }
        } else if (ttsReady) {
            startSpeaking(index, text)
        }
    }

    private fun startSpeaking(index: Int, text: String) {
        // Bengali text -> Bengali voice, otherwise English. When the
        // phone has no Bengali voice data installed, say so honestly.
        // (Bengali Unicode block U+0980..U+09FF, as code points.)
        val locale = if (text.any { it.code in 0x0980..0x09FF }) Locale("bn") else Locale.ENGLISH
        val result = tts?.setLanguage(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Toast.makeText(this, getString(R.string.notes_read_lang_missing), Toast.LENGTH_LONG).show()
            return
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                runOnUiThread { if (speakingIndex == index) speakingIndex = null }
            }

            override fun onError(utteranceId: String?) {
                runOnUiThread { if (speakingIndex == index) speakingIndex = null }
            }
        })
        speakingIndex = index
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kitty_ai_chat")
    }

    private fun stopSpeaking() {
        tts?.stop()
        speakingIndex = null
    }

    // ---- Voice input ----

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.notes_ai_chat_speak_prompt))
        }
        try {
            speakLauncher.launch(intent)
        } catch (_: Exception) {
            Toast.makeText(
                this, getString(R.string.notes_ai_chat_voice_failed), Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ---- Image generation ----

    private fun askCreateImage() {
        if (answering) return
        val promptInput = EditText(this).apply {
            hint = getString(R.string.notes_ai_chat_image_prompt)
            minLines = 2
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.notes_ai_chat_create_image))
            .setView(promptInput)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val prompt = promptInput.text.toString().trim()
                if (prompt.isBlank()) return@setPositiveButton
                createImage(prompt)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun createImage(prompt: String) {
        addUserMessage(prompt, null)
        val liveIndex = addMessage(
            AiChatMessage(
                text = getString(R.string.notes_ai_chat_typing), isUser = false, isTyping = true
            )
        )
        answering = true
        updateSendButton()
        answerJob = lifecycleScope.launch {
            try {
                val result = GeminiSummarizer.generateImageBytes(this@AiChatActivity, prompt)
                result.onSuccess { bytes ->
                    messages[liveIndex] = AiChatMessage(text = "", isUser = false, image = bytes)
                    adapter.notifyItemChanged(liveIndex)
                    history.add(GeminiSummarizer.ChatTurn("assistant", "[Image generated]"))
                    saveMessage("assistant", "", bytes)
                }.onFailure { e ->
                    messages.removeAt(liveIndex)
                    adapter.notifyItemRemoved(liveIndex)
                    val msg = if (e is IOException) {
                        getString(R.string.notes_ai_failed)
                    } else {
                        // Image output is not on Gemini's free tier.
                        getString(R.string.notes_ai_chat_image_paid)
                    }
                    Toast.makeText(this@AiChatActivity, msg, Toast.LENGTH_LONG).show()
                }
            } finally {
                val live = messages.getOrNull(liveIndex)
                if (live != null && live.isTyping) {
                    messages.removeAt(liveIndex)
                    adapter.notifyItemRemoved(liveIndex)
                }
                answering = false
                updateSendButton()
            }
        }
    }

    /** Long-press on a picture message: saves it to Pictures/Kitty Notes (Gallery). */
    private fun saveImageToGallery(bytes: ByteArray) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        throw UnsupportedOperationException("API 29+ required")
                    }
                    val fileName = "AI image " + System.currentTimeMillis() + ".png"
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                        put(
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            Environment.DIRECTORY_PICTURES + "/Kitty Notes"
                        )
                    }
                    val resolver = contentResolver
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("MediaStore insert failed")
                    resolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: throw IllegalStateException("Could not open Gallery")
                    fileName
                }
            }
            Toast.makeText(
                this@AiChatActivity,
                getString(
                    if (result.isSuccess) R.string.notes_ai_chat_image_saved
                    else R.string.notes_ai_chat_image_save_failed
                ),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ---- Save as note / export / new chat ----

    private fun buildTranscript(): String {
        val sb = StringBuilder()
        for (m in messages) {
            if (m.isTyping || m.isLive) continue
            sb.append(if (m.isUser) "You: " else "AI: ")
                .append(m.text.ifBlank { "[Image]" }).append("\n\n")
        }
        return sb.toString().trim()
    }

    /** Opens the editor with the whole conversation prefilled, so it can be saved as a note. */
    private fun saveChatAsNote() {
        if (messages.none { !it.isTyping && !it.isLive }) {
            Toast.makeText(this, getString(R.string.notes_ai_chat_empty), Toast.LENGTH_SHORT).show()
            return
        }
        val title = messages.firstOrNull { it.isUser }?.text.orEmpty()
            .take(60).ifBlank { getString(R.string.notes_ai_chat) }
        startActivity(
            Intent(this, NoteEditorActivity::class.java)
                .putExtra(NoteEditorActivity.EXTRA_TEMPLATE_TITLE, title)
                .putExtra(NoteEditorActivity.EXTRA_TEMPLATE_CONTENT, buildTranscript())
        )
    }

    private fun askNewChat() {
        if (messages.isEmpty() || answering) return
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.notes_ai_chat_new_confirm))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                stopSpeaking()
                messages.clear()
                history.clear()
                chatId = -1
                noteContext = null
                pendingImage = null
                previewRow.visibility = View.GONE
                adapter.notifyDataSetChanged()
                updateChipsVisibility()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
