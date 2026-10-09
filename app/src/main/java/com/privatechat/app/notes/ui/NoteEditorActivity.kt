package com.privatechat.app.notes.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
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
import com.privatechat.app.R
import com.privatechat.app.notes.data.Category
import com.privatechat.app.notes.data.ChecklistItem
import com.privatechat.app.notes.data.Note
import com.privatechat.app.notes.data.NoteImage
import com.privatechat.app.notes.data.NoteImageStore
import com.privatechat.app.notes.data.NotesRepository
import com.privatechat.app.notes.reminder.ReminderManager
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
    private var imagesJob: Job? = null
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_note_editor)

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
            val names = listOf(getString(R.string.notes_no_tag)) + categories.map { it.name }
            categorySpinner.adapter = ArrayAdapter(this@NoteEditorActivity, android.R.layout.simple_spinner_dropdown_item, names)

            if (noteId != 0L) {
                repo.getNote(noteId)?.let { n ->
                    titleInput.setText(n.title)
                    contentInput.setText(n.content)
                    pinned = n.pinned
                    createdAt = n.createdAt
                    noteColor = n.color
                    reminderAt = n.reminderAt
                    val idx = if (n.categoryId == null) 0 else categories.indexOfFirst { it.id == n.categoryId } + 1
                    if (idx in names.indices) categorySpinner.setSelection(idx)
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
            buildColorPalette()
            observeImages()
            loaded = true
        }
    }

    private fun selectedCategoryId(): Long? {
        val pos = categorySpinner.selectedItemPosition
        return if (pos <= 0) null else categories.getOrNull(pos - 1)?.id
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = lifecycleScope.launch {
            delay(800)
            persist(showToast = false)
        }
    }

    /** The note body as stored/searched/shared (checklist-aware). */
    private fun currentBodyText(): String =
        if (checklistMode) renderChecklistText() else contentInput.text.toString()

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
        val note = Note(
            id = noteId,
            title = title.trim(),
            content = content,
            categoryId = selectedCategoryId(),
            pinned = pinned,
            createdAt = createdAt,
            updatedAt = System.currentTimeMillis(),
            color = noteColor,
            isChecklist = checklistMode,
            reminderAt = reminderAt
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
        }
    }

    private fun saveAndFinish() {
        saveJob?.cancel()
        lifecycleScope.launch {
            persistNow()
            finish()
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
            getString(R.string.notes_reminder_set) + ": " + DATE_FMT.format(Date(it))
        } ?: getString(R.string.notes_reminder_none)
        reminderClear.visibility = if (reminderAt != null) View.VISIBLE else View.GONE
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
                            updateReminderLabel()
                            scheduleSave()
                            maybeOfferExactAlarmSettings()
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

    private fun showPhoto(image: NoteImage) {
        val imageView = ImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            adjustViewBounds = true
        }
        Glide.with(this)
            .load(NoteImageStore.file(this, image.fileName))
            .fitCenter()
            .into(imageView)
        AlertDialog.Builder(this)
            .setView(imageView)
            .setPositiveButton(android.R.string.ok, null)
            .show()
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
            contentInput.text.toString().trim()
        }
        return if (title.isEmpty()) body else "$title\n\n$body"
    }

    private fun writeExport(uri: Uri, asPdf: Boolean) {
        lifecycleScope.launch {
            val ok = runCatching {
                if (asPdf) {
                    writePdf(uri, exportText())
                } else {
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(exportText().toByteArray(Charsets.UTF_8))
                    } ?: error("no stream")
                }
            }.isSuccess
            Toast.makeText(
                this@NoteEditorActivity,
                getString(if (ok) R.string.notes_exported else R.string.notes_export_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** Renders plain text into a simple multi-page A4 PDF (text only). */
    private suspend fun writePdf(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        val doc = PdfDocument()
        try {
            val pageWidth = 595
            val pageHeight = 842
            val margin = 40f
            val maxWidth = pageWidth - margin * 2
            val titlePaint = Paint().apply { textSize = 19f; isFakeBoldText = true; color = Color.BLACK }
            val bodyPaint = Paint().apply { textSize = 12f; color = Color.DKGRAY }

            val lines = mutableListOf<Pair<String, Paint>>()
            text.lineSequence().forEachIndexed { index, raw ->
                val paint = if (index == 0 && titleInput.text.isNotBlank()) titlePaint else bodyPaint
                wrapLine(raw, paint, maxWidth).forEach { lines.add(it to paint) }
            }
            if (lines.isEmpty()) lines.add("" to bodyPaint)

            var pageNumber = 1
            var currentPage: PdfDocument.Page? = null
            var canvas: android.graphics.Canvas? = null
            var y = margin + 20f
            fun newPage() {
                val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                val page = doc.startPage(info)
                currentPage = page
                canvas = page.canvas
                y = margin + 20f
                pageNumber++
            }
            newPage()
            lines.forEach { (line, paint) ->
                val lineHeight = paint.textSize + 7f
                if (y + lineHeight > pageHeight - margin) {
                    doc.finishPage(currentPage!!)
                    newPage()
                }
                canvas?.drawText(line, margin, y, paint)
                y += lineHeight
            }
            doc.finishPage(currentPage!!)
            contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) } ?: error("no stream")
        } finally {
            doc.close()
        }
    }

    private fun wrapLine(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isEmpty()) return listOf("")
        val out = mutableListOf<String>()
        var rest = text
        while (rest.isNotEmpty()) {
            val count = paint.breakText(rest, true, maxWidth, null)
            if (count <= 0 || count >= rest.length) {
                out.add(rest)
                break
            }
            var cut = rest.lastIndexOf(' ', count)
            if (cut <= 0) cut = count
            out.add(rest.substring(0, cut).trimEnd())
            rest = rest.substring(cut).trimStart()
        }
        return out
    }

    override fun onPause() {
        super.onPause()
        // Final safety save when leaving; persist() is a no-op for empty notes.
        if (loaded) persist(showToast = false)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        saveAndFinish()
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
