package com.privatechat.app.notes.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.snackbar.Snackbar
import com.privatechat.app.R
import com.privatechat.app.notes.data.Category
import com.privatechat.app.notes.data.ChecklistItem
import com.privatechat.app.notes.data.Note
import com.privatechat.app.notes.data.NoteImage
import com.privatechat.app.notes.data.NoteImageStore
import com.privatechat.app.notes.data.NotesRepository
import com.privatechat.app.notes.data.VoiceNote
import com.privatechat.app.notes.reminder.ReminderManager
import com.privatechat.app.notes.security.NoteLockAuth
import com.privatechat.app.notes.util.Countdown
import com.privatechat.app.notes.util.DocumentScan
import com.privatechat.app.notes.util.GeminiSummarizer
import com.privatechat.app.notes.util.LinkPreviewFetcher
import com.privatechat.app.notes.util.LoadingDialog
import com.privatechat.app.notes.util.NoteImageShare
import com.privatechat.app.notes.util.NoteMarkdown
import com.privatechat.app.notes.util.NoteSmart
import com.privatechat.app.notes.util.PdfExporter
import com.privatechat.app.notes.util.QrShare
import com.privatechat.app.notes.util.RichText
import com.privatechat.app.notes.util.SmartReminderDetector
import com.privatechat.app.notes.util.TextScanner
import com.privatechat.app.notes.util.VoiceNoteStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Create/edit a single note. Auto-saves with a debounce and always
 * saves the latest state when leaving, without ever creating a
 * duplicate for the same editing session.
 *
 * Kitty Notes Plus (additive only — the original text editing flow is
 * unchanged): pastel note color, checklist mode, photo attachments,
 * one-shot reminder, duplicate, and TXT/PDF export.
 */
class NoteEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NOTE_ID = "note_id"
        const val EXTRA_TEMPLATE_TITLE = "template_title"
        const val EXTRA_TEMPLATE_CONTENT = "template_content"
        private val DATE_FMT = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault())

        /** Selectable pastel card colors; "" (first entry) = default. */
        private val NOTE_COLORS = listOf(
            "", "#FDE2E4", "#FFE5D9", "#FFF3B0", "#D8F3DC", "#CAF0F8", "#E7C6FF"
        )
    }

    private val repo by lazy { NotesRepository(this) }

    private lateinit var titleInput: EditText
    private lateinit var contentInput: EditText
    private lateinit var categorySpinner: Spinner
    private lateinit var metaText: TextView
    private lateinit var colorRow: LinearLayout
    private lateinit var checklistToggle: TextView
    private lateinit var checklistBox: LinearLayout
    private lateinit var checklistItemsBox: LinearLayout
    private lateinit var reminderValue: TextView
    private lateinit var reminderClear: TextView
    private lateinit var imagesList: RecyclerView

    // ---- Note Preview state ----
    private lateinit var editorScroll: View
    private lateinit var previewView: WebView
    private lateinit var previewButton: TextView
    private var previewMode = false

    private var noteId: Long = 0
    private var categories: List<Category> = emptyList()
    private var pinned: Boolean = false
    private var createdAt: Long = System.currentTimeMillis()
    private var saveJob: Job? = null
    private var loaded = false

    // ---- Kitty Notes Plus state ----
    private var noteColor: String = ""
    private var checklistMode: Boolean = false
    private val checklist = mutableListOf<ChecklistItem>()
    private var reminderAt: Long? = null
    private var reminderRepeat: String = "NONE"
    /** AI-suggested tags, comma-separated, "" = none. */
    private var currentTags: String = ""
    private var imagesJob: Job? = null

    // ---- Study pack state ----
    /** Per-note lock (Notes password / fingerprint). */
    private var noteLocked: Boolean = false
    /** Self-destruct time (epoch millis), null = never expires. */
    private var noteExpiresAt: Long? = null
    /** Countdown target date (epoch millis, local midnight), null = no countdown. */
    private var noteCountdownAt: Long? = null
    /** Read aloud (TextToSpeech). */
    private var tts: TextToSpeech? = null
    private var ttsReady: Boolean = false
    private var readingAloud: Boolean = false

    // ---- Smart pack state ----
    private var voiceRecorder: MediaRecorder? = null
    private var voicePlayer: MediaPlayer? = null
    private var voiceFile: java.io.File? = null
    private var voiceTranscript: String = ""
    // WhatsApp-style recording bar state (pause-aware elapsed time).
    private var voicePaused = false
    private var voiceRecordedMs: Long = 0
    private var voiceSegmentStartMs: Long = 0
    private var voiceTimerView: TextView? = null
    private var voicePauseView: TextView? = null
    private val voiceTimerHandler = Handler(Looper.getMainLooper())
    private val voiceTimerTick = object : Runnable {
        override fun run() {
            updateVoiceTimer()
            if (voiceRecorder != null && !voicePaused) {
                voiceTimerHandler.postDelayed(this, 250)
            }
        }
    }
    private var speechRecognizer: SpeechRecognizer? = null
    private var voiceJob: Job? = null
    private var lastReminderOffer: Long? = null
    private var lastLinkUrl: String? = null
    /** True when the loaded note stores **bold** markers (e.g. a scanned note). */
    private var boldMarkersPresent = false
    private lateinit var imagesAdapter: EditorImagesAdapter

    private val pickPhoto = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) attachPhoto(uri) }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { openDatePicker() }

    private val exportTxtLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> if (uri != null) writeExport(uri, asPdf = false) }

    private val exportPdfLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri -> if (uri != null) writeExport(uri, asPdf = true) }

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startVoiceRecording()
        else Toast.makeText(this, getString(R.string.notes_voice_no_mic), Toast.LENGTH_SHORT).show()
    }

    /**
     * Scan document (Smart tools): the document scanner captures the
     * page, OCR reads the text out of the photo, and a NEW note
     * opens with that text and an auto title. The old flow only
     * opened the scanned PDF, and pulling text out of that PDF gave
     * binary gibberish for scanned pages — OCR reads the photo
     * itself, so the note gets the real words.
     */
    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val pageUris = DocumentScan.pageUrisFromResult(result.data)
        if (pageUris.isEmpty()) {
            Toast.makeText(this, getString(R.string.notes_scan_failed), Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        val progress = AlertDialog.Builder(this)
            .setMessage(getString(R.string.notes_scan_reading))
            .setCancelable(false)
            .create()
        progress.show()
        lifecycleScope.launch {
            persistNow() // save the note being edited before opening the new one
            val useAi = GeminiSummarizer.getApiKey(this@NoteEditorActivity) != null
            if (useAi) progress.setMessage(getString(R.string.notes_scan_ai_fixing))
            // Same per-page flow as the home screen's scan: ML Kit OCR,
            // then (with a saved AI key) the vision pass, which reads
            // the photo itself — restores punctuation, marks
            // __underlined__ text, rebuilds tables as | pipe | tables,
            // and fills unreadable words from context, wrapping each
            // guess in [? ?]. Any step that fails falls back to that
            // page's plain OCR text.
            val pageTexts = mutableListOf<String>()
            var aiFailed = false
            for (uri in pageUris) {
                val md = withContext(Dispatchers.IO) {
                    TextScanner.recognizeUri(this@NoteEditorActivity, uri).getOrNull()?.markdownText
                }
                if (md.isNullOrBlank()) continue
                val fixed = if (useAi) {
                    val jpeg = withContext(Dispatchers.IO) {
                        GeminiSummarizer.pageJpegBytes(this@NoteEditorActivity, uri)
                    }
                    if (jpeg != null) {
                        GeminiSummarizer.fixScannedPage(this@NoteEditorActivity, jpeg, md).getOrNull()
                    } else null
                } else null
                if (useAi && fixed == null) aiFailed = true
                pageTexts += fixed ?: md
            }
            if (pageTexts.isEmpty()) {
                progress.dismiss()
                Toast.makeText(
                    this@NoteEditorActivity,
                    getString(R.string.notes_scan_no_text),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            var md = pageTexts.joinToString("\n\n").trim()
            if (!useAi) {
                GeminiSummarizer.cleanOcrText(this@NoteEditorActivity, md).onSuccess { cleaned ->
                    md = cleaned
                }
            }
            val plain = md.replace("**", "").replace("__", "")
            val title = GeminiSummarizer.smartTitle(this@NoteEditorActivity, plain)
            // Save first so the page photos can be attached to the
            // note (photos live on a note id, max 10), then open it.
            val noteId = withContext(Dispatchers.IO) {
                repo.saveNote(Note(title = title, content = md))
            }
            withContext(Dispatchers.IO) {
                pageUris.take(NoteImageStore.MAX_IMAGES_PER_NOTE).forEach { uri ->
                    NoteImageStore.importImage(this@NoteEditorActivity, uri)?.let { fileName ->
                        repo.addImageRecord(noteId, fileName)
                    }
                }
            }
            progress.dismiss()
            startActivity(
                Intent(this@NoteEditorActivity, NoteEditorActivity::class.java)
                    .putExtra(EXTRA_NOTE_ID, noteId)
            )
            Toast.makeText(
                this@NoteEditorActivity,
                getString(
                    if (aiFailed) R.string.notes_scan_ai_failed
                    else R.string.notes_scan_text_saved
                ),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_note_editor)
        overridePendingTransition(R.anim.slide_in_right, R.anim.fade_out)

        titleInput = findViewById(R.id.editorTitle)
        contentInput = findViewById(R.id.editorContent)
        categorySpinner = findViewById(R.id.editorCategory)
        metaText = findViewById(R.id.editorMeta)
        colorRow = findViewById(R.id.editorColorRow)
        checklistToggle = findViewById(R.id.editorChecklistToggle)
        checklistBox = findViewById(R.id.editorChecklistBox)
        checklistItemsBox = findViewById(R.id.editorChecklistItems)
        reminderValue = findViewById(R.id.editorReminderValue)
        reminderClear = findViewById(R.id.editorReminderClear)
        imagesList = findViewById(R.id.editorImages)

        editorScroll = findViewById(R.id.editorScroll)
        previewView = findViewById(R.id.editorPreview)
        previewButton = findViewById(R.id.editorPreviewButton)
        previewView.settings.javaScriptEnabled = false
        previewButton.setOnClickListener { togglePreview() }

        findViewById<View>(R.id.editorBack).setOnClickListener { saveAndFinish() }
        findViewById<View>(R.id.editorSave).setOnClickListener { saveAndFinish() }
        findViewById<View>(R.id.editorPin).setOnClickListener {
            pinned = !pinned
            Toast.makeText(this, if (pinned) getString(R.string.notes_pinned) else getString(R.string.notes_unpinned), Toast.LENGTH_SHORT).show()
            scheduleSave()
        }
        findViewById<View>(R.id.editorShare).setOnClickListener { shareNote() }
        findViewById<View>(R.id.editorCopy).setOnClickListener {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("note", buildShareText()))
            Toast.makeText(this, getString(R.string.notes_copied), Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.editorDelete).setOnClickListener { confirmTrash() }

        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { if (loaded) scheduleSave() }
        }
        titleInput.addTextChangedListener(watcher)
        contentInput.addTextChangedListener(watcher)
        categorySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { if (loaded) scheduleSave() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // ---- Kitty Notes Plus wiring (new controls only) ----
        checklistToggle.setOnClickListener { setChecklistMode(!checklistMode) }
        findViewById<View>(R.id.editorChecklistAdd).setOnClickListener {
            checklist.add(ChecklistItem(noteId = noteId, text = "", position = checklist.size))
            renderChecklistRows(focusLast = true)
            scheduleSave()
        }
        findViewById<View>(R.id.editorReminderSet).setOnClickListener { startReminderFlow() }
        reminderClear.setOnClickListener {
            reminderAt = null
            reminderRepeat = "NONE"
            updateReminderLabel()
            scheduleSave()
        }
        findViewById<View>(R.id.editorAddPhoto).setOnClickListener {
            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<View>(R.id.editorDuplicate).setOnClickListener { duplicateCurrent() }
        findViewById<View>(R.id.editorExportTxt).setOnClickListener {
            exportTxtLauncher.launch(suggestedFileName(".txt"))
        }
        findViewById<View>(R.id.editorExportPdf).setOnClickListener {
            exportPdfLauncher.launch(suggestedFileName(".pdf"))
        }

        // ---- Smart pack wiring (new controls only) ----
        findViewById<View>(R.id.editorVoice).setOnClickListener { onVoiceButton() }
        findViewById<View>(R.id.editorSummary).setOnClickListener { summarizeNote() }
        findViewById<View>(R.id.editorQrShare).setOnClickListener { showQrShare() }
        findViewById<View>(R.id.editorScanDoc).setOnClickListener {
            DocumentScan.launch(
                this,
                DocumentScan.createScanner(this),
                scanLauncher
            ) {
                Toast.makeText(this, getString(R.string.notes_scan_failed), Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<View>(R.id.editorShareImage).setOnClickListener { shareNoteAsImage() }
        findViewById<View>(R.id.editorSavePdf).setOnClickListener { savePdfToDownloads() }
        findViewById<View>(R.id.editorSaveImage).setOnClickListener { saveNoteAsImage() }
        findViewById<View>(R.id.editorAutoCategory).setOnClickListener { suggestCategory() }
        findViewById<View>(R.id.editorFixText).setOnClickListener { fixTextWithAi() }

        // ---- Study pack wiring (new controls only) ----
        findViewById<View>(R.id.editorReadAloud).setOnClickListener { toggleReadAloud() }
        findViewById<View>(R.id.editorFont).setOnClickListener { showFontDialog() }
        findViewById<View>(R.id.editorLock).setOnClickListener { toggleNoteLock() }
        findViewById<View>(R.id.editorSelfDestruct).setOnClickListener { showSelfDestructDialog() }
        findViewById<View>(R.id.editorCountdown).setOnClickListener { showCountdownDialog() }
        imagesAdapter = EditorImagesAdapter(
            onRemove = { image ->
                lifecycleScope.launch { repo.removeImage(image) }
            },
            onOpen = { image -> showPhoto(image) }
        )
        imagesList.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        imagesList.adapter = imagesAdapter
        updateChecklistToggleLabel()
        updateReminderLabel()

        noteId = intent.getLongExtra(EXTRA_NOTE_ID, 0)
        lifecycleScope.launch {
            categories = repo.observeCategories().first()
            bindCategorySpinner()

            if (noteId != 0L) {
                repo.getNote(noteId)?.let { n ->
                    titleInput.setText(n.title)
                    contentInput.setText(n.content)
                    pinned = n.pinned
                    createdAt = n.createdAt
                    noteColor = n.color
                    reminderAt = n.reminderAt
                    reminderRepeat = n.reminderRepeat
                    currentTags = n.tags
                    noteLocked = n.locked
                    noteExpiresAt = n.expiresAt
                    noteCountdownAt = n.countdownAt
                    updateLockLabel()
                    updateSelfDestructLabel()
                    updateCountdownLabel()
                    bindCategorySpinner(n.categoryId)
                    metaText.text = getString(R.string.notes_last_edited, DATE_FMT.format(Date(n.updatedAt)))
                    if (n.isChecklist) {
                        val items = repo.getChecklistItems(n.id)
                        checklist.clear()
                        if (items.isNotEmpty()) {
                            checklist.addAll(items)
                        } else if (n.content.isNotBlank()) {
                            // e.g. restored from a backup: rebuild items
                            // from the note's plain-text rendering.
                            checklist.addAll(parseChecklistText(n.content))
                        }
                        applyChecklistVisibility(true)
                    }
                    updateReminderLabel()
                }
            } else {
                intent.getStringExtra(EXTRA_TEMPLATE_TITLE)?.let { titleInput.setText(it) }
                intent.getStringExtra(EXTRA_TEMPLATE_CONTENT)?.let { contentInput.setText(it) }
                metaText.text = getString(R.string.notes_new_note)
            }
            updateTagsView()
            applyBoldMarkersIfPresent()
            applyEditorFont()
            buildColorPalette()
            observeImages()
            loaded = true
            observeVoiceNotes()
            checkSmartFeatures()
        }
    }

    private fun selectedCategoryId(): Long? {
        val pos = categorySpinner.selectedItemPosition
        return if (pos <= 0) null else categories.getOrNull(pos - 1)?.id
    }

    /** Rebuilds the category spinner and selects the given category (null = none). */
    private fun bindCategorySpinner(selectId: Long? = null) {
        val names = listOf(getString(R.string.notes_no_tag)) + categories.map { it.name }
        categorySpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        val idx = if (selectId == null) 0 else categories.indexOfFirst { it.id == selectId } + 1
        if (idx in names.indices) categorySpinner.setSelection(idx)
    }

    /** Shows the AI tags under the category spinner; hidden when there are none. */
    private fun updateTagsView() {
        val tags = currentTags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        findViewById<TextView>(R.id.editorTags).apply {
            text = tags.joinToString(" ") { "#$it" }
            visibility = if (tags.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = lifecycleScope.launch {
            delay(800)
            persist(showToast = false)
        }
    }

    /**
     * Bold/underline in the editor: a note that stores **markers**
     * or __markers__ (a scanned note) shows the words bold and
     * underlined, with the markers hidden. Saving converts the
     * spans back to markers (see [currentBodyText]), so the
     * round-trip is stable. Notes without markers are untouched —
     * their save path is byte-identical to before.
     */
    private fun applyBoldMarkersIfPresent() {
        val text = contentInput.text.toString()
        if (!RichText.hasMarkers(text)) return
        boldMarkersPresent = true
        contentInput.setText(RichText.toSpanned(text))
    }

    /** The note body as stored/searched/shared (checklist-aware). */
    private fun currentBodyText(): String =
        if (checklistMode) renderChecklistText()
        else if (boldMarkersPresent) RichText.toMarkdown(contentInput.text)
        else contentInput.text.toString()

    private fun renderChecklistText(): String =
        checklist.filter { it.text.isNotBlank() }
            .joinToString("\n") { (if (it.checked) "✓ " else "☐ ") + it.text.trim() }

    private fun parseChecklistText(text: String): List<ChecklistItem> =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.mapIndexed { index, line ->
            val checked = line.startsWith("✓ ") || line.startsWith("[x] ")
            val clean = line.removePrefix("✓ ").removePrefix("☐ ")
                .removePrefix("[x] ").removePrefix("[ ] ")
            ChecklistItem(noteId = noteId, text = clean, checked = checked, position = index)
        }

    /**
     * Saves the current editor state. Returns the note id, or 0 when
     * there is nothing worth saving yet. [force] allows creating the
     * row for a photo-first note (photo is the content).
     */
    private suspend fun persistNow(force: Boolean = false): Long {
        val title = titleInput.text.toString()
        val content = currentBodyText()
        if (title.isBlank() && content.isBlank() && !force) return 0
        // Auto title: when the title is blank, the first content line
        // becomes the saved title (the field shows it on the next open).
        val effectiveTitle = NoteSmart.autoTitle(title, content)
        val note = Note(
            id = noteId,
            title = effectiveTitle.trim(),
            content = content,
            categoryId = selectedCategoryId(),
            pinned = pinned,
            createdAt = createdAt,
            updatedAt = System.currentTimeMillis(),
            color = noteColor,
            isChecklist = checklistMode,
            reminderAt = reminderAt,
            reminderRepeat = reminderRepeat,
            tags = currentTags,
            locked = noteLocked,
            expiresAt = noteExpiresAt,
            countdownAt = noteCountdownAt
        )
        noteId = repo.saveNote(note)
        if (checklistMode) {
            repo.replaceChecklistItems(noteId, checklist.filter { it.text.isNotBlank() })
        } else {
            repo.clearChecklistItems(noteId)
        }
        // Keep the alarm in step with what was just saved.
        ReminderManager.sync(this, note.copy(id = noteId))
        return noteId
    }

    private fun persist(showToast: Boolean) {
        lifecycleScope.launch {
            if (persistNow() > 0 && showToast) {
                Toast.makeText(this@NoteEditorActivity, getString(R.string.notes_saved), Toast.LENGTH_SHORT).show()
            }
            checkSmartFeatures()
        }
    }

    // ---------------- study pack: read aloud ----------------

    /** Applies the saved editor font (size to the body, family to both fields). */
    private fun applyEditorFont() {
        EditorFont.applyTo(this, contentInput, applySize = true)
        EditorFont.applyTo(this, titleInput, applySize = false)
    }

    /** The note as speech: title + body, without **bold** markers and checklist glyphs. */
    private fun buildSpeechText(): String {
        val title = titleInput.text.toString().trim()
        val body = currentBodyText()
            .replace("**", "")
            .replace("✓ ", "").replace("☐ ", "")
            .trim()
        return listOf(title, body).filter { it.isNotBlank() }.joinToString(". ")
    }

    private fun toggleReadAloud() {
        if (readingAloud) {
            stopReading()
            return
        }
        val text = buildSpeechText()
        if (text.isBlank()) {
            Toast.makeText(this, getString(R.string.notes_read_nothing), Toast.LENGTH_SHORT).show()
            return
        }
        if (tts == null) {
            tts = TextToSpeech(this) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) startReading(text)
                else Toast.makeText(this, getString(R.string.notes_read_no_tts), Toast.LENGTH_SHORT).show()
            }
        } else if (ttsReady) {
            startReading(text)
        }
    }

    private fun startReading(text: String) {
        // Bengali text -> Bengali voice, otherwise English. When the
        // phone has no Bengali voice data installed, say so honestly
        // instead of reading Bengali in an English voice.
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
                runOnUiThread { stopReading() }
            }

            override fun onError(utteranceId: String?) {
                runOnUiThread { stopReading() }
            }
        })
        readingAloud = true
        updateReadAloudLabel()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kitty_note_read")
    }

    private fun stopReading() {
        tts?.stop()
        readingAloud = false
        updateReadAloudLabel()
    }

    private fun updateReadAloudLabel() {
        findViewById<TextView>(R.id.editorReadAloud).text =
            getString(if (readingAloud) R.string.notes_read_stop else R.string.notes_read_aloud)
        refreshToolStates()
    }

    // ---------------- study pack: font ----------------

    /** Font dialog: size (13/15/19sp) + family (default/sans/serif/mono). Applies live. */
    private fun showFontDialog() {
        val dp = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), 0)
        }
        val sizeLabel = TextView(this).apply {
            text = getString(R.string.notes_font_size)
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@NoteEditorActivity, R.color.textSecondary))
        }
        val sizeGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val sizeValues = listOf(EditorFont.SIZE_SMALL, EditorFont.SIZE_MEDIUM, EditorFont.SIZE_LARGE)
        val sizeNames = listOf(
            getString(R.string.notes_font_size_small),
            getString(R.string.notes_font_size_medium),
            getString(R.string.notes_font_size_large)
        )
        val sizeButtons = sizeValues.mapIndexed { index, size ->
            RadioButton(this).apply {
                id = View.generateViewId()
                text = sizeNames[index]
                setOnClickListener {
                    EditorFont.setSize(this@NoteEditorActivity, size)
                    applyEditorFont()
                }
            }.also { sizeGroup.addView(it) }
        }
        sizeGroup.check(sizeButtons[sizeValues.indexOf(EditorFont.getSize(this)).coerceIn(sizeValues.indices)].id)
        val familyLabel = TextView(this).apply {
            text = getString(R.string.notes_font_family)
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@NoteEditorActivity, R.color.textSecondary))
            setPadding(0, (16 * dp).toInt(), 0, 0)
        }
        val familyGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val familyValues = listOf("default", "sans", "serif", "mono")
        val familyNames = listOf(
            getString(R.string.notes_font_family_default),
            getString(R.string.notes_font_family_sans),
            getString(R.string.notes_font_family_serif),
            getString(R.string.notes_font_family_mono)
        )
        val familyButtons = familyValues.mapIndexed { index, family ->
            RadioButton(this).apply {
                id = View.generateViewId()
                text = familyNames[index]
                setOnClickListener {
                    EditorFont.setFamily(this@NoteEditorActivity, family)
                    applyEditorFont()
                }
            }.also { familyGroup.addView(it) }
        }
        familyGroup.check(familyButtons[familyValues.indexOf(EditorFont.getFamily(this)).coerceIn(familyValues.indices)].id)
        container.addView(sizeLabel)
        container.addView(sizeGroup)
        container.addView(familyLabel)
        container.addView(familyGroup)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.notes_font_title))
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ---------------- study pack: per-note lock ----------------

    private fun updateLockLabel() {
        findViewById<TextView>(R.id.editorLock).text =
            getString(if (noteLocked) R.string.notes_unlock_note else R.string.notes_lock_note)
        refreshToolStates()
    }

    /** Lock/unlock this note. Locking needs a Notes password (created here if none exists). */
    private fun toggleNoteLock() {
        if (noteLocked) {
            noteLocked = false
            NoteLockAuth.markLocked(noteId)
            updateLockLabel()
            scheduleSave()
            Toast.makeText(this, getString(R.string.notes_note_unlocked_toast), Toast.LENGTH_SHORT).show()
            return
        }
        ensureSavedNote {
            NoteLockAuth.ensurePassword(this) {
                noteLocked = true
                updateLockLabel()
                scheduleSave()
                Toast.makeText(this, getString(R.string.notes_note_locked_toast), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------------- study pack: self-destruct ----------------

    private fun updateSelfDestructLabel() {
        findViewById<TextView>(R.id.editorSelfDestruct).text = when (val at = noteExpiresAt) {
            null -> getString(R.string.notes_self_destruct)
            else -> {
                val days = ((at - System.currentTimeMillis()) / 86_400_000L).toInt()
                if (days >= 1) getString(R.string.notes_self_destruct_days_left, days)
                else getString(R.string.notes_self_destruct_today)
            }
        }
        refreshToolStates()
    }

    // ---------------- countdown note ----------------

    private fun updateCountdownLabel() {
        val label = findViewById<TextView>(R.id.editorCountdown)
        val at = noteCountdownAt
        label.text = when {
            at == null -> getString(R.string.notes_countdown)
            else -> {
                val days = Countdown.daysUntil(at)
                when {
                    days > 0 -> getString(R.string.notes_countdown_days_left, days)
                    days == 0 -> getString(R.string.notes_countdown_today)
                    else -> getString(R.string.notes_countdown_days_ago, -days)
                }
            }
        }
        refreshToolStates()
    }

    /** Countdown: pick a target date; the card counts the days left. No alarm, no deletion. */
    private fun showCountdownDialog() {
        ensureSavedNote {
            val cal = java.util.Calendar.getInstance()
            noteCountdownAt?.let { cal.timeInMillis = it }
            val dialog = DatePickerDialog(
                this,
                { _, year, month, day ->
                    val picked = java.util.Calendar.getInstance().apply {
                        set(year, month, day, 0, 0, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    noteCountdownAt = picked
                    updateCountdownLabel()
                    scheduleSave()
                },
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH),
                cal.get(java.util.Calendar.DAY_OF_MONTH)
            )
            dialog.datePicker.minDate = Countdown.startOfDay(System.currentTimeMillis())
            dialog.setTitle(getString(R.string.notes_countdown_title))
            dialog.setButton(
                android.content.DialogInterface.BUTTON_NEUTRAL,
                getString(R.string.notes_self_destruct_off)
            ) { _, _ ->
                noteCountdownAt = null
                updateCountdownLabel()
                scheduleSave()
            }
            dialog.show()
        }
    }

    /**
     * Selected-state tint: a tool that is ON right now shows red text,
     * everything else keeps the normal purple. Tap again to turn it off.
     * One-tap tools (Add photo, Scan document, Save PDF…) open something
     * immediately, so they have no ON state to show.
     */
    private fun refreshToolStates() {
        val red = ContextCompat.getColor(this, R.color.kittyError)
        val normal = ContextCompat.getColor(this, R.color.primary)
        findViewById<TextView>(R.id.editorChecklistToggle)
            .setTextColor(if (checklistMode) red else normal)
        findViewById<TextView>(R.id.editorVoice)
            .setTextColor(if (voiceRecorder != null) red else normal)
        findViewById<TextView>(R.id.editorReadAloud)
            .setTextColor(if (readingAloud) red else normal)
        findViewById<TextView>(R.id.editorReminderSet)
            .setTextColor(if (reminderAt != null) red else normal)
        findViewById<TextView>(R.id.editorCountdown)
            .setTextColor(if (noteCountdownAt != null) red else normal)
        findViewById<TextView>(R.id.editorSelfDestruct)
            .setTextColor(if (noteExpiresAt != null) red else normal)
        findViewById<TextView>(R.id.editorLock)
            .setTextColor(if (noteLocked) red else normal)
    }

    /** Self-destruct: 7 / 30 / 90 days, or off. Expired notes go to Trash, never deleted outright. */
    private fun showSelfDestructDialog() {
        ensureSavedNote {
            val dayOptions = listOf(7, 30, 90)
            val labels = arrayOf(
                getString(R.string.notes_self_destruct_7),
                getString(R.string.notes_self_destruct_30),
                getString(R.string.notes_self_destruct_90),
                getString(R.string.notes_self_destruct_off)
            )
            val checked = when (val at = noteExpiresAt) {
                null -> 3
                else -> {
                    val days = ((at - System.currentTimeMillis()) / 86_400_000L).toInt()
                    dayOptions.indices.minByOrNull { kotlin.math.abs(dayOptions[it] - days) } ?: 1
                }
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.notes_self_destruct_title))
                .setSingleChoiceItems(labels, checked) { dialog, which ->
                    noteExpiresAt = if (which == 3) null
                    else System.currentTimeMillis() + dayOptions[which] * 86_400_000L
                    updateSelfDestructLabel()
                    scheduleSave()
                    Toast.makeText(
                        this,
                        if (noteExpiresAt == null) getString(R.string.notes_self_destruct_off_toast)
                        else getString(R.string.notes_self_destruct_set, dayOptions[which]),
                        Toast.LENGTH_SHORT
                    ).show()
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** Lock and self-destruct need a saved row: persist a new note first. */
    private fun ensureSavedNote(onReady: () -> Unit) {
        if (noteId != 0L) {
            onReady()
            return
        }
        lifecycleScope.launch {
            persistNow()
            if (noteId != 0L) onReady()
            else Toast.makeText(this@NoteEditorActivity, getString(R.string.notes_save_first), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Preview / Edit toggle (top bar). Preview renders the CURRENT
     * title + content through NoteMarkdown, so it always matches
     * what is on screen — tables drawn with real borders, bold and
     * underline as printed. The note itself stays plain text, and
     * tapping Edit returns to the text fields untouched.
     */
    private fun togglePreview() {
        previewMode = !previewMode
        if (previewMode) {
            val html = NoteMarkdown.toHtml(
                titleInput.text.toString(),
                contentInput.text.toString(),
                hexColor(R.color.background),
                hexColor(R.color.textPrimary)
            )
            previewView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            editorScroll.visibility = View.GONE
            previewView.visibility = View.VISIBLE
            previewButton.text = getString(R.string.notes_preview_edit)
        } else {
            previewView.visibility = View.GONE
            editorScroll.visibility = View.VISIBLE
            previewButton.text = getString(R.string.notes_preview)
        }
    }

    private fun hexColor(@androidx.annotation.ColorRes id: Int): String {
        val c = ContextCompat.getColor(this, id)
        return String.format("#%06X", 0xFFFFFF and c)
    }

    private fun saveAndFinish() {
        saveJob?.cancel()
        // Show the auto title the note will be saved with.
        if (titleInput.text.isBlank()) {
            val auto = NoteSmart.autoTitle("", currentBodyText())
            if (auto.isNotBlank()) titleInput.setText(auto)
        }
        lifecycleScope.launch {
            persistNow()
            finish()
            overridePendingTransition(R.anim.fade_in, R.anim.slide_out_left)
        }
    }

    private fun buildShareText(): String {
        val t = titleInput.text.toString().trim()
        val c = currentBodyText().trim()
        return if (t.isEmpty()) c else "$t\n\n$c"
    }

    private fun shareNote() {
        val text = buildShareText()
        if (text.isBlank()) {
            Toast.makeText(this, getString(R.string.notes_nothing_to_share), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, titleInput.text.toString().trim())
        }
        startActivity(Intent.createChooser(intent, getString(R.string.notes_share_note)))
    }

    private fun confirmTrash() {
        if (noteId == 0L) { finish(); return }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.notes_move_to_trash_title))
            .setMessage(getString(R.string.notes_move_to_trash_msg))
            .setPositiveButton(getString(R.string.notes_move_to_trash)) { _, _ ->
                lifecycleScope.launch {
                    repo.getNote(noteId)?.let { repo.moveToTrash(it) }
                    ReminderManager.cancel(this@NoteEditorActivity, noteId)
                    finish()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------- color ----------------

    private fun buildColorPalette() {
        colorRow.removeAllViews()
        val size = (36 * resources.displayMetrics.density).toInt()
        val margin = (5 * resources.displayMetrics.density).toInt()
        NOTE_COLORS.forEach { hex ->
            val dot = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = margin }
                gravity = android.view.Gravity.CENTER
                textSize = 13f
                text = if (hex.isEmpty()) "∅" else ""
                setTextColor(getColor(R.color.textSecondary))
                contentDescription = if (hex.isEmpty()) getString(R.string.notes_color_none) else hex
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(
                        if (hex.isEmpty()) getColor(R.color.surface)
                        else runCatching { Color.parseColor(hex) }.getOrDefault(Color.WHITE)
                    )
                    val selected = hex == noteColor
                    setStroke(
                        if (selected) 3 else 1,
                        getColor(if (selected) R.color.kittyAccent else R.color.textSecondary)
                    )
                }
                setOnClickListener {
                    noteColor = hex
                    buildColorPalette()
                    scheduleSave()
                }
            }
            colorRow.addView(dot)
        }
    }

    // ---------------- checklist ----------------

    private fun updateChecklistToggleLabel() {
        checklistToggle.text = (if (checklistMode) "☑ " else "☐ ") + getString(R.string.notes_checklist)
        refreshToolStates()
    }

    private fun setChecklistMode(on: Boolean) {
        if (on == checklistMode) return
        if (on) {
            if (checklist.isEmpty()) {
                checklist.addAll(parseChecklistText(contentInput.text.toString()))
                if (checklist.isEmpty()) {
                    checklist.add(ChecklistItem(noteId = noteId, text = "", position = 0))
                }
            }
        } else {
            contentInput.setText(renderChecklistText())
        }
        applyChecklistVisibility(on)
        scheduleSave()
    }

    private fun applyChecklistVisibility(on: Boolean) {
        checklistMode = on
        checklistBox.visibility = if (on) View.VISIBLE else View.GONE
        contentInput.visibility = if (on) View.GONE else View.VISIBLE
        updateChecklistToggleLabel()
        if (on) renderChecklistRows(focusLast = false)
    }

    private fun renderChecklistRows(focusLast: Boolean) {
        checklistItemsBox.removeAllViews()
        val density = resources.displayMetrics.density
        checklist.forEachIndexed { index, item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val check = CheckBox(this).apply {
                isChecked = item.checked
                setOnCheckedChangeListener { _, checked ->
                    checklist[index] = checklist[index].copy(checked = checked)
                    if (loaded) scheduleSave()
                }
            }
            val edit = EditText(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setText(item.text)
                hint = getString(R.string.notes_checklist_hint)
                textSize = 15f
                background = null
                setTextColor(getColor(R.color.textPrimary))
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setPadding((4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt())
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        checklist[index] = checklist[index].copy(text = s?.toString().orEmpty())
                        if (loaded) scheduleSave()
                    }
                })
            }
            val remove = TextView(this).apply {
                text = "✕"
                textSize = 14f
                setTextColor(getColor(R.color.kittyError))
                setPadding((8 * density).toInt(), (6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt())
                setOnClickListener {
                    checklist.removeAt(index)
                    if (checklist.isEmpty()) {
                        checklist.add(ChecklistItem(noteId = noteId, text = "", position = 0))
                    }
                    renderChecklistRows(focusLast = false)
                    scheduleSave()
                }
            }
            row.addView(check)
            row.addView(edit)
            row.addView(remove)
            checklistItemsBox.addView(row)
            if (focusLast && index == checklist.lastIndex) {
                edit.requestFocus()
                edit.setSelection(edit.text.length)
            }
        }
    }

    // ---------------- reminder ----------------

    private fun updateReminderLabel() {
        reminderValue.text = reminderAt?.let {
            val base = getString(R.string.notes_reminder_set) + ": " + DATE_FMT.format(Date(it))
            val repeat = when (reminderRepeat) {
                "DAILY" -> " • " + getString(R.string.notes_reminder_daily)
                "WEEKLY" -> " • " + getString(R.string.notes_reminder_weekly)
                else -> ""
            }
            base + repeat
        } ?: getString(R.string.notes_reminder_none)
        reminderClear.visibility = if (reminderAt != null) View.VISIBLE else View.GONE
        refreshToolStates()
    }

    private fun startReminderFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openDatePicker()
        }
    }

    private fun openDatePicker() {
        val base = Calendar.getInstance().apply {
            reminderAt?.let { timeInMillis = it }
        }
        DatePickerDialog(
            this,
            { _, year, month, day ->
                TimePickerDialog(
                    this,
                    { _, hour, minute ->
                        val cal = Calendar.getInstance().apply {
                            set(Calendar.YEAR, year)
                            set(Calendar.MONTH, month)
                            set(Calendar.DAY_OF_MONTH, day)
                            set(Calendar.HOUR_OF_DAY, hour)
                            set(Calendar.MINUTE, minute)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        if (cal.timeInMillis <= System.currentTimeMillis()) {
                            Toast.makeText(this, getString(R.string.notes_reminder_future_only), Toast.LENGTH_SHORT).show()
                        } else {
                            reminderAt = cal.timeInMillis
                            askReminderRepeat()
                        }
                    },
                    base.get(Calendar.HOUR_OF_DAY),
                    base.get(Calendar.MINUTE),
                    false
                ).apply { setTitle(getString(R.string.notes_reminder_pick_time)) }.show()
            },
            base.get(Calendar.YEAR),
            base.get(Calendar.MONTH),
            base.get(Calendar.DAY_OF_MONTH)
        ).apply { setTitle(getString(R.string.notes_reminder_pick_date)) }.show()
    }

    /** After picking a time, ask whether it should repeat. */
    private fun askReminderRepeat() {
        val labels = arrayOf(
            getString(R.string.notes_reminder_none_repeat),
            getString(R.string.notes_reminder_daily),
            getString(R.string.notes_reminder_weekly)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.notes_reminder_repeat))
            .setItems(labels) { _, which ->
                reminderRepeat = when (which) {
                    1 -> "DAILY"
                    2 -> "WEEKLY"
                    else -> "NONE"
                }
                updateReminderLabel()
                scheduleSave()
                maybeOfferExactAlarmSettings()
            }
            .setOnCancelListener {
                reminderRepeat = "NONE"
                updateReminderLabel()
                scheduleSave()
                maybeOfferExactAlarmSettings()
            }
            .show()
    }

    private fun maybeOfferExactAlarmSettings() {
        if (ReminderManager.canScheduleExact(this)) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.notes_reminder_exact_title))
            .setMessage(getString(R.string.notes_reminder_exact_msg))
            .setPositiveButton(getString(R.string.notes_reminder_open_settings)) { _, _ ->
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                }
            }
            .setNegativeButton(getString(R.string.notes_reminder_continue), null)
            .show()
    }

    // ---------------- photos ----------------

    private fun observeImages() {
        imagesJob?.cancel()
        if (noteId == 0L) {
            imagesAdapter.submitList(emptyList())
            return
        }
        imagesJob = lifecycleScope.launch {
            repo.observeImages(noteId).collectLatest { images ->
                imagesAdapter.submitList(images)
            }
        }
    }

    private fun attachPhoto(uri: Uri) {
        lifecycleScope.launch {
            if (noteId == 0L && persistNow(force = true) == 0L) {
                Toast.makeText(this@NoteEditorActivity, getString(R.string.notes_photo_failed), Toast.LENGTH_SHORT).show()
                return@launch
            }
            observeImages()
            val existing = repo.getImages(noteId)
            if (existing.size >= NoteImageStore.MAX_IMAGES_PER_NOTE) {
                Toast.makeText(
                    this@NoteEditorActivity,
                    getString(R.string.notes_photo_limit, NoteImageStore.MAX_IMAGES_PER_NOTE),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            val fileName = NoteImageStore.importImage(this@NoteEditorActivity, uri)
            if (fileName == null) {
                Toast.makeText(this@NoteEditorActivity, getString(R.string.notes_photo_failed), Toast.LENGTH_SHORT).show()
            } else {
                repo.addImageRecord(noteId, fileName)
            }
        }
    }

    /**
     * Full-screen photo viewer. The old version put the ImageView in an
     * AlertDialog with no layout params: the dialog measured it before
     * Glide finished loading, so it stayed 0-size and the dialog showed
     * blank white. A full-screen dialog with MATCH_PARENT params (tap
     * anywhere to close) shows the photo properly.
     */
    private fun showPhoto(image: NoteImage) {
        val imageView = ImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        Glide.with(this)
            .load(NoteImageStore.file(this, image.fileName))
            .fitCenter()
            .into(imageView)
        Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            setContentView(imageView)
            setCancelable(true)
            setCanceledOnTouchOutside(true)
            imageView.setOnClickListener { dismiss() }
            window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            show()
        }
    }

    // ---------------- duplicate & export ----------------

    private fun duplicateCurrent() {
        lifecycleScope.launch {
            if (persistNow() == 0L) {
                Toast.makeText(this@NoteEditorActivity, getString(R.string.notes_nothing_to_share), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val source = repo.getNote(noteId) ?: return@launch
            repo.duplicateNote(source)
            Toast.makeText(this@NoteEditorActivity, getString(R.string.notes_duplicated), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun suggestedFileName(extension: String): String {
        val base = titleInput.text.toString().trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(60)
        return (base.ifBlank { "kitty-note" }) + extension
    }

    private fun exportText(): String {
        val title = titleInput.text.toString().trim()
        val body = if (checklistMode) {
            checklist.filter { it.text.isNotBlank() }
                .joinToString("\n") { (if (it.checked) "[x] " else "[ ] ") + it.text.trim() }
        } else {
            currentBodyText().trim()
        }
        return if (title.isEmpty()) body else "$title\n\n$body"
    }

    private fun writeExport(uri: Uri, asPdf: Boolean) {
        lifecycleScope.launch {
            val ok = runCatching {
                if (asPdf) {
                    PdfExporter.writeToUri(
                        this@NoteEditorActivity, uri,
                        titleInput.text.toString().trim(), currentBodyText()
                    )
                } else {
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(exportText().toByteArray(Charsets.UTF_8))
                    } ?: error("no stream")
                }
            }.isSuccess
            if (asPdf && ok) {
                AlertDialog.Builder(this@NoteEditorActivity)
                    .setTitle(getString(R.string.notes_pdf_saved_title))
                    .setMessage(uri.lastPathSegment ?: getString(R.string.notes_exported))
                    .setPositiveButton(getString(R.string.notes_pdf_open)) { _, _ ->
                        startActivity(
                            Intent(this@NoteEditorActivity, PdfViewerActivity::class.java)
                                .putExtra(PdfViewerActivity.EXTRA_PDF_URI, uri.toString())
                        )
                    }
                    .setNegativeButton(android.R.string.ok, null)
                    .show()
            } else {
                Toast.makeText(
                    this@NoteEditorActivity,
                    getString(if (ok) R.string.notes_exported else R.string.notes_export_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ---------------- smart pack ----------------

    private fun onVoiceButton() {
        if (voiceRecorder != null) {
            // The recording bar (pause / ✓ save / ✕ discard) is the
            // control while recording; tapping again does nothing.
            Toast.makeText(this, getString(R.string.notes_voice_recording), Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startVoiceRecording()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceRecording() {
        lifecycleScope.launch {
            if (noteId == 0L && persistNow(force = true) == 0L) {
                Toast.makeText(this@NoteEditorActivity, getString(R.string.notes_voice_no_mic), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val file = VoiceNoteStore.newFile(this@NoteEditorActivity, noteId)
            voiceFile = file
            voiceRecordedMs = 0
            voiceSegmentStartMs = System.currentTimeMillis()
            voicePaused = false
            voiceTranscript = ""
            runCatching {
                voiceRecorder = VoiceNoteStore.createRecorder(this@NoteEditorActivity, file).apply {
                    prepare()
                    start()
                }
                startLiveTranscription()
                showVoiceRecBar()
                voiceTimerHandler.post(voiceTimerTick)
            }.onFailure {
                voiceRecorder = null
                file.delete()
                Toast.makeText(this@NoteEditorActivity, getString(R.string.notes_voice_no_mic), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** ✓ on the recording bar: stop and save the recording as a voice note. */
    private fun saveVoiceRecording() {
        val recorder = voiceRecorder ?: return
        val file = voiceFile
        if (!voicePaused) voiceRecordedMs += System.currentTimeMillis() - voiceSegmentStartMs
        voiceRecorder = null
        voicePaused = false
        voiceTimerHandler.removeCallbacks(voiceTimerTick)
        hideVoiceRecBar()
        speechRecognizer?.stopListening()
        runCatching { recorder.stop() }
        runCatching { recorder.release() }
        if (file == null || !file.exists()) return
        val duration = voiceRecordedMs
        voiceRecordedMs = 0
        val transcript = voiceTranscript.trim()
        lifecycleScope.launch {
            repo.addVoiceNote(
                VoiceNote(noteId = noteId, fileName = file.name, durationMs = duration, transcript = transcript)
            )
            // The note may have been created by this recording; make
            // sure the voice list is observing the real note id now.
            observeVoiceNotes()
            Toast.makeText(this@NoteEditorActivity, getString(R.string.notes_voice_saved), Toast.LENGTH_SHORT).show()
        }
    }

    /** ✕ on the recording bar: stop and throw the recording away. */
    private fun discardVoiceRecording() {
        val recorder = voiceRecorder ?: return
        val file = voiceFile
        voiceRecorder = null
        voicePaused = false
        voiceTimerHandler.removeCallbacks(voiceTimerTick)
        hideVoiceRecBar()
        speechRecognizer?.stopListening()
        runCatching { recorder.stop() }
        runCatching { recorder.release() }
        runCatching { file?.delete() }
        voiceRecordedMs = 0
        Toast.makeText(this, getString(R.string.notes_voice_discarded), Toast.LENGTH_SHORT).show()
    }

    /** ⏸ / ▶ on the recording bar. MediaRecorder.pause needs API 24+ (minSdk is 24). */
    private fun togglePauseVoiceRecording() {
        val recorder = voiceRecorder ?: return
        if (!voicePaused) {
            if (runCatching { recorder.pause() }.isFailure) return
            voiceRecordedMs += System.currentTimeMillis() - voiceSegmentStartMs
            voicePaused = true
            voicePauseView?.text = "▶"
            voiceTimerHandler.removeCallbacks(voiceTimerTick)
            updateVoiceTimer()
        } else {
            if (runCatching { recorder.resume() }.isFailure) return
            voiceSegmentStartMs = System.currentTimeMillis()
            voicePaused = false
            voicePauseView?.text = "⏸"
            voiceTimerHandler.post(voiceTimerTick)
        }
    }

    private fun currentRecordedMs(): Long =
        if (voiceRecorder == null || voicePaused) voiceRecordedMs
        else voiceRecordedMs + (System.currentTimeMillis() - voiceSegmentStartMs)

    private fun updateVoiceTimer() {
        voiceTimerView?.text = VoiceNoteStore.formatDuration(currentRecordedMs())
    }

    /**
     * WhatsApp-style recording bar: red dot + live timer, pause/resume,
     * ✓ to save as a voice note, ✕ to discard. Built in code so the
     * saved-rows list in editorVoiceBox is never disturbed.
     */
    private fun showVoiceRecBar() {
        val bar = findViewById<LinearLayout>(R.id.editorVoiceRecBar)
        bar.removeAllViews()
        val density = resources.displayMetrics.density
        val pad = (9 * density).toInt()
        val dot = TextView(this).apply {
            text = "●"
            textSize = 14f
            setTextColor(getColor(R.color.kittyError))
            setPadding((12 * density).toInt(), pad, (4 * density).toInt(), pad)
        }
        val timer = TextView(this).apply {
            text = VoiceNoteStore.formatDuration(0)
            textSize = 14f
            setTextColor(getColor(R.color.textPrimary))
            setPadding((4 * density).toInt(), pad, (4 * density).toInt(), pad)
        }
        voiceTimerView = timer
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val pause = TextView(this).apply {
            text = "⏸"
            textSize = 20f
            setTextColor(getColor(R.color.primary))
            setPadding((12 * density).toInt(), pad, (12 * density).toInt(), pad)
            setOnClickListener { togglePauseVoiceRecording() }
        }
        voicePauseView = pause
        val save = TextView(this).apply {
            text = "✓"
            textSize = 20f
            setTextColor(getColor(R.color.primary))
            setPadding((12 * density).toInt(), pad, (12 * density).toInt(), pad)
            setOnClickListener { saveVoiceRecording() }
        }
        val discard = TextView(this).apply {
            text = "✕"
            textSize = 20f
            setTextColor(getColor(R.color.kittyError))
            setPadding((12 * density).toInt(), pad, (12 * density).toInt(), pad)
            setOnClickListener { discardVoiceRecording() }
        }
        bar.addView(dot)
        bar.addView(timer)
        bar.addView(spacer)
        bar.addView(pause)
        bar.addView(save)
        bar.addView(discard)
        bar.visibility = View.VISIBLE
        refreshToolStates()
    }

    private fun hideVoiceRecBar() {
        findViewById<LinearLayout>(R.id.editorVoiceRecBar).visibility = View.GONE
        voiceTimerView = null
        voicePauseView = null
        refreshToolStates()
    }

    /**
     * Best-effort live transcription while recording (on-device
     * speech recognition; needs the Google app / internet on most
     * phones). If it is unavailable the audio is still saved and
     * the transcript simply stays empty.
     */
    private fun startLiveTranscription() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {}
            override fun onPartialResults(partialResults: Bundle?) {
                val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!list.isNullOrEmpty()) voiceTranscript = list[0]
            }

            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!list.isNullOrEmpty()) voiceTranscript = list[0]
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        runCatching { recognizer.startListening(intent) }
    }

    private fun observeVoiceNotes() {
        voiceJob?.cancel()
        if (noteId == 0L) {
            findViewById<LinearLayout>(R.id.editorVoiceBox).visibility = View.GONE
            return
        }
        voiceJob = lifecycleScope.launch {
            repo.observeVoiceNotes(noteId).collectLatest { list ->
                renderVoiceRows(list)
            }
        }
    }

    private fun renderVoiceRows(list: List<VoiceNote>) {
        val box = findViewById<LinearLayout>(R.id.editorVoiceBox)
        box.removeAllViews()
        box.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        val density = resources.displayMetrics.density
        list.forEach { voice ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val play = TextView(this).apply {
                text = "▶ " + VoiceNoteStore.formatDuration(voice.durationMs)
                textSize = 13f
                setTextColor(getColor(R.color.primary))
                setPadding((9 * density).toInt(), (9 * density).toInt(), (9 * density).toInt(), (9 * density).toInt())
                setOnClickListener { playVoiceNote(voice) }
            }
            val label = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = voice.transcript
                textSize = 13f
                setTextColor(getColor(R.color.textSecondary))
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                visibility = if (voice.transcript.isBlank()) View.GONE else View.VISIBLE
            }
            val remove = TextView(this).apply {
                text = "✕"
                textSize = 14f
                setTextColor(getColor(R.color.kittyError))
                setPadding((9 * density).toInt(), (9 * density).toInt(), (9 * density).toInt(), (9 * density).toInt())
                setOnClickListener {
                    lifecycleScope.launch { repo.deleteVoiceNote(voice) }
                }
            }
            row.addView(play)
            row.addView(label)
            row.addView(remove)
            box.addView(row)
        }
    }

    private fun playVoiceNote(voice: VoiceNote) {
        runCatching { voicePlayer?.release() }
        voicePlayer = null
        val file = java.io.File(VoiceNoteStore.dir(this), voice.fileName)
        if (!file.exists()) {
            Toast.makeText(this, getString(R.string.notes_voice_missing), Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            voicePlayer = VoiceNoteStore.createPlayer(file).apply {
                setOnCompletionListener { runCatching { release() }; voicePlayer = null }
                start()
            }
        }
    }

    private fun summarizeNote() {
        val text = currentBodyText().trim()
        if (text.length < 200) {
            Toast.makeText(this, getString(R.string.notes_summary_empty), Toast.LENGTH_SHORT).show()
            return
        }
        if (GeminiSummarizer.getApiKey(this) == null) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.notes_summary_title))
                .setMessage(getString(R.string.notes_summary_no_key))
                .setPositiveButton(getString(R.string.notes_settings_title)) { _, _ ->
                    startActivity(Intent(this, NotesSettingsActivity::class.java))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        // Language choice (last used language pre-selected, remembered
        // for next time). Tapping a language runs the summary at once.
        val langs = arrayOf("বাংলা", "English")
        val checked = if (GeminiSummarizer.getSummaryLang(this) == "en") 1 else 0
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.notes_summary_language))
            .setSingleChoiceItems(langs, checked) { dialog, which ->
                val lang = if (which == 1) "en" else "bn"
                GeminiSummarizer.saveSummaryLang(this, lang)
                dialog.dismiss()
                runSummary(text, lang)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runSummary(text: String, lang: String) {
        val loading = AlertDialog.Builder(this)
            .setTitle(getString(R.string.notes_summary_title))
            .setMessage("…")
            .setCancelable(false)
            .create()
        loading.show()
        lifecycleScope.launch {
            val result = GeminiSummarizer.summarize(this@NoteEditorActivity, text, lang)
            loading.dismiss()
            result.onSuccess { summary ->
                AlertDialog.Builder(this@NoteEditorActivity)
                    .setTitle(getString(R.string.notes_summary_title))
                    .setMessage(summary)
                    .setPositiveButton(android.R.string.copy) { _, _ ->
                        val cm = getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("summary", summary))
                    }
                    .setNegativeButton(android.R.string.ok, null)
                    .show()
            }.onFailure {
                Toast.makeText(this@NoteEditorActivity, getString(R.string.notes_summary_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------------- AI category/tags + fix text ----------------

    /** Shared "no Gemini key" dialog (same destination as the summary flow). */
    private fun showAiNoKeyDialog(titleRes: Int) {
        AlertDialog.Builder(this)
            .setTitle(getString(titleRes))
            .setMessage(getString(R.string.notes_ai_no_key))
            .setPositiveButton(getString(R.string.notes_settings_title)) { _, _ ->
                startActivity(Intent(this, NotesSettingsActivity::class.java))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Auto category + tags: Gemini reads the note and suggests the best
     * existing category (or a short new one) plus a few tags. Nothing
     * is applied silently — the suggestion is shown first, and Apply
     * sets it (creating the category when it is new).
     */
    private fun suggestCategory() {
        val text = (titleInput.text.toString() + "\n" + currentBodyText()).trim()
        if (text.length < 20) {
            Toast.makeText(this, getString(R.string.notes_ai_too_short), Toast.LENGTH_SHORT).show()
            return
        }
        if (GeminiSummarizer.getApiKey(this) == null) {
            showAiNoKeyDialog(R.string.notes_ai_category_title)
            return
        }
        val loading = AlertDialog.Builder(this)
            .setTitle(getString(R.string.notes_ai_category_title))
            .setMessage("…")
            .setCancelable(false)
            .create()
        loading.show()
        lifecycleScope.launch {
            val result = GeminiSummarizer.suggestCategory(
                this@NoteEditorActivity, text, categories.map { it.name }
            )
            loading.dismiss()
            result.onSuccess { suggestion ->
                val tagsText = suggestion.tags.joinToString(", ") { "#$it" }
                val message = getString(R.string.notes_ai_suggests_category, suggestion.category) +
                    if (tagsText.isEmpty()) "" else "\n" + getString(R.string.notes_ai_suggests_tags, tagsText)
                AlertDialog.Builder(this@NoteEditorActivity)
                    .setTitle(getString(R.string.notes_ai_category_title))
                    .setMessage(message)
                    .setPositiveButton(getString(R.string.notes_ai_apply)) { _, _ ->
                        applyCategoryAndTags(suggestion)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }.onFailure {
                Toast.makeText(this@NoteEditorActivity, getString(R.string.notes_ai_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applyCategoryAndTags(suggestion: GeminiSummarizer.CategorySuggestion) {
        lifecycleScope.launch {
            val match = categories.firstOrNull { it.name.equals(suggestion.category, ignoreCase = true) }
            val categoryId = match?.id ?: run {
                val newId = repo.addCategory(suggestion.category)
                categories = repo.getCategoriesOnce()
                newId
            }
            currentTags = suggestion.tags.joinToString(",")
            bindCategorySpinner(categoryId)
            updateTagsView()
            scheduleSave()
            Toast.makeText(
                this@NoteEditorActivity,
                getString(R.string.notes_ai_category_applied), Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Fixes the current note's text with AI (OCR misreads, split words,
     * stray symbols) — same cleanup the scan flow runs automatically.
     * Wording, case and line breaks are kept; ** bold markers are
     * re-rendered bold afterwards.
     */
    private fun fixTextWithAi() {
        val text = currentBodyText().trim()
        if (text.length < 20) {
            Toast.makeText(this, getString(R.string.notes_ai_too_short), Toast.LENGTH_SHORT).show()
            return
        }
        if (GeminiSummarizer.getApiKey(this) == null) {
            showAiNoKeyDialog(R.string.notes_ai_fix_text)
            return
        }
        val loading = AlertDialog.Builder(this)
            .setTitle(getString(R.string.notes_ai_fix_text))
            .setMessage("…")
            .setCancelable(false)
            .create()
        loading.show()
        lifecycleScope.launch {
            val result = GeminiSummarizer.cleanOcrText(this@NoteEditorActivity, text)
            loading.dismiss()
            result.onSuccess { cleaned ->
                if (cleaned == text) {
                    Toast.makeText(
                        this@NoteEditorActivity,
                        getString(R.string.notes_ai_text_clean), Toast.LENGTH_SHORT
                    ).show()
                    return@onSuccess
                }
                boldMarkersPresent = false
                contentInput.setText(cleaned)
                applyBoldMarkersIfPresent()
                scheduleSave()
                Toast.makeText(
                    this@NoteEditorActivity,
                    getString(R.string.notes_ai_text_fixed), Toast.LENGTH_SHORT
                ).show()
            }.onFailure {
                Toast.makeText(this@NoteEditorActivity, getString(R.string.notes_ai_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showQrShare() {
        val text = buildShareText()
        if (text.isBlank()) {
            Toast.makeText(this, getString(R.string.notes_nothing_to_share), Toast.LENGTH_SHORT).show()
            return
        }
        val parts = QrShare.splitForQr(text)
        if (parts.size > 1) {
            Toast.makeText(this, getString(R.string.notes_qr_too_big), Toast.LENGTH_SHORT).show()
        }
        var index = 0
        var currentBitmap: Bitmap? = null
        val image = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(600, 600)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val label = TextView(this).apply {
            gravity = android.view.Gravity.CENTER
            textSize = 14f
            setTextColor(getColor(R.color.textPrimary))
            setPadding(0, 16, 0, 0)
        }
        val prev = Button(this).apply { this.text = "←" }
        val next = Button(this).apply { this.text = "→" }
        fun render() {
            currentBitmap = QrShare.encodeToBitmap(parts[index], 600)
            image.setImageBitmap(currentBitmap)
            label.text = if (parts.size > 1) "${index + 1} / ${parts.size}" else ""
        }
        prev.setOnClickListener { if (index > 0) { index--; render() } }
        next.setOnClickListener { if (index < parts.size - 1) { index++; render() } }
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            addView(prev)
            addView(next)
        }
        // Download (saves the shown QR to Downloads) and Share (sends
        // the shown QR image to any app) — both act on the part that
        // is currently displayed.
        val download = Button(this).apply {
            this.text = getString(R.string.notes_qr_download)
            setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.stat_sys_download, 0, 0, 0)
            compoundDrawablePadding = 16
        }
        val share = Button(this).apply {
            this.text = getString(R.string.notes_share_short)
            setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_share, 0, 0, 0)
            compoundDrawablePadding = 16
        }
        download.setOnClickListener { downloadQr(currentBitmap, index + 1, parts.size) }
        share.setOnClickListener { shareQr(currentBitmap) }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(0, 24, 0, 0)
            addView(download)
            addView(share)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(48, 24, 48, 8)
            addView(image)
            addView(label)
            addView(actions)
            if (parts.size > 1) addView(nav)
        }
        render()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.notes_qr_show))
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun downloadQr(bitmap: Bitmap?, part: Int, total: Int) {
        if (bitmap == null) {
            Toast.makeText(this, getString(R.string.notes_export_failed), Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val result = QrShare.saveToGallery(this@NoteEditorActivity, bitmap, part, total)
            result.onSuccess { name ->
                Toast.makeText(this@NoteEditorActivity, getString(R.string.notes_qr_saved, name), Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@NoteEditorActivity, getString(R.string.notes_export_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareQr(bitmap: Bitmap?) {
        if (bitmap == null) {
            Toast.makeText(this, getString(R.string.notes_export_failed), Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            runCatching {
                val uri = QrShare.shareBitmap(this@NoteEditorActivity, bitmap)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.notes_share_note)))
            }.onFailure {
                Toast.makeText(this@NoteEditorActivity, getString(R.string.notes_export_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Saves the same image "Share as image" sends, to Pictures/Kitty Notes (Gallery). */
    private fun saveNoteAsImage() {
        val text = buildShareText()
        if (text.isBlank()) {
            Toast.makeText(this, getString(R.string.notes_nothing_to_share), Toast.LENGTH_SHORT).show()
            return
        }
        val progress = LoadingDialog.show(this)
        lifecycleScope.launch {
            val result = NoteImageShare.saveNoteImageToGallery(
                this@NoteEditorActivity,
                titleInput.text.toString().trim(),
                currentBodyText(),
                noteColor
            )
            progress.dismiss()
            result.onSuccess { name ->
                Toast.makeText(this@NoteEditorActivity, getString(R.string.notes_image_saved, name), Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@NoteEditorActivity, getString(R.string.notes_export_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareNoteAsImage() {
        val text = buildShareText()
        if (text.isBlank()) {
            Toast.makeText(this, getString(R.string.notes_nothing_to_share), Toast.LENGTH_SHORT).show()
            return
        }
        val progress = LoadingDialog.show(this)
        lifecycleScope.launch {
            runCatching {
                val uri = NoteImageShare.shareNoteAsImage(
                    this@NoteEditorActivity,
                    titleInput.text.toString().trim(),
                    currentBodyText(),
                    noteColor
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.notes_share_note)))
            }.onFailure {
                progress.dismiss()
                Toast.makeText(this@NoteEditorActivity, getString(R.string.notes_export_failed), Toast.LENGTH_SHORT).show()
            }
            // Success path opens the share sheet above; dismiss here so
            // the dialog never lingers behind the chooser.
            if (progress.isShowing) progress.dismiss()
        }
    }

    private fun savePdfToDownloads() {
        val progress = LoadingDialog.show(this)
        lifecycleScope.launch {
            val result = PdfExporter.saveToDownloads(
                this@NoteEditorActivity,
                titleInput.text.toString().trim(),
                currentBodyText()
            )
            progress.dismiss()
            result.onSuccess { (name, uri) ->
                AlertDialog.Builder(this@NoteEditorActivity)
                    .setTitle(getString(R.string.notes_pdf_saved_title))
                    .setMessage(getString(R.string.notes_pdf_saved, name))
                    .setPositiveButton(getString(R.string.notes_pdf_open)) { _, _ ->
                        startActivity(
                            Intent(this@NoteEditorActivity, PdfViewerActivity::class.java)
                                .putExtra(PdfViewerActivity.EXTRA_PDF_URI, uri.toString())
                        )
                    }
                    .setNegativeButton(android.R.string.ok, null)
                    .show()
            }.onFailure {
                // Older phones (API < 29) cannot use MediaStore
                // Downloads: fall back to the file picker instead.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    exportPdfLauncher.launch(suggestedFileName(".pdf"))
                } else {
                    Toast.makeText(this@NoteEditorActivity, getString(R.string.notes_export_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Cheap smart checks after a save: reminder offer, total, link card. */
    private fun checkSmartFeatures() {
        if (!loaded || isFinishing) return
        val body = currentBodyText()
        // Reminder from text (offer once per detected time).
        SmartReminderDetector.detect(body, System.currentTimeMillis())?.let { detected ->
            if (reminderAt == null && detected.triggerMillis != lastReminderOffer) {
                lastReminderOffer = detected.triggerMillis
                Snackbar.make(
                    contentInput,
                    getString(R.string.notes_reminder_suggest, detected.label),
                    Snackbar.LENGTH_LONG
                ).setAction(getString(R.string.notes_reminder_set)) {
                    reminderAt = detected.triggerMillis
                    reminderRepeat = "NONE"
                    updateReminderLabel()
                    scheduleSave()
                }.show()
            }
        }
        // Expense total.
        val total = NoteSmart.calculateTotal(body)
        val calcView = findViewById<TextView>(R.id.editorCalcTotal)
        if (total != null) {
            calcView.text = getString(R.string.notes_calc_total, NoteSmart.formatTotal(total))
            calcView.visibility = View.VISIBLE
        } else {
            calcView.visibility = View.GONE
        }
        // Link preview (fetch only when the link changes).
        val url = LinkPreviewFetcher.findFirstUrl(body)
        if (url == null) {
            lastLinkUrl = null
            findViewById<View>(R.id.editorLinkCard).visibility = View.GONE
            return
        }
        if (url == lastLinkUrl) return
        lastLinkUrl = url
        lifecycleScope.launch {
            val preview = LinkPreviewFetcher.fetch(url)
            if (preview == null) {
                findViewById<View>(R.id.editorLinkCard).visibility = View.GONE
                return@launch
            }
            findViewById<TextView>(R.id.editorLinkTitle).text = preview.title
            val imageView = findViewById<ImageView>(R.id.editorLinkImage)
            if (preview.imageUrl != null) {
                Glide.with(this@NoteEditorActivity).load(preview.imageUrl).into(imageView)
            } else {
                imageView.setImageDrawable(null)
            }
            val card = findViewById<View>(R.id.editorLinkCard)
            card.visibility = View.VISIBLE
            card.setOnClickListener {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(preview.url)))
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Stop reading when leaving the editor (a dialog opening does
        // not pause the activity, so the font/lock dialogs keep playing).
        if (readingAloud) stopReading()
        // Final safety save when leaving; persist() is a no-op for empty notes.
        if (loaded) persist(showToast = false)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        saveAndFinish()
    }

    override fun onDestroy() {
        // Leaving mid-recording: stop the timer, release the recorder
        // and drop the unfinished file (nothing is saved).
        voiceTimerHandler.removeCallbacks(voiceTimerTick)
        if (voiceRecorder != null) runCatching { voiceFile?.delete() }
        runCatching { voiceRecorder?.release() }
        runCatching { voicePlayer?.release() }
        speechRecognizer?.destroy()
        // Read aloud: release the speech engine.
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        super.onDestroy()
    }

    /** Photo thumbnails for the editor (Glide keeps scrolling smooth). */
    private class EditorImagesAdapter(
        private val onRemove: (NoteImage) -> Unit,
        private val onOpen: (NoteImage) -> Unit
    ) : RecyclerView.Adapter<EditorImagesAdapter.VH>() {

        private val items = mutableListOf<NoteImage>()

        fun submitList(list: List<NoteImage>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_editor_image, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val image = items[position]
            Glide.with(holder.itemView.context)
                .load(NoteImageStore.file(holder.itemView.context, image.fileName))
                .centerCrop()
                .into(holder.thumb)
            holder.remove.setOnClickListener { onRemove(image) }
            holder.thumb.setOnClickListener { onOpen(image) }
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val thumb: ImageView = v.findViewById(R.id.editorImageThumb)
            val remove: TextView = v.findViewById(R.id.editorImageRemove)
        }
    }
}
