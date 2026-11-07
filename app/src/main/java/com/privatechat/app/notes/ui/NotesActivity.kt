package com.privatechat.app.notes.ui

import android.animation.ObjectAnimator
import android.content.Intent
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.navigation.NavigationView
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.privatechat.app.R
import com.privatechat.app.data.AccessGate
import com.privatechat.app.notes.backup.BackupWorker
import com.privatechat.app.notes.backup.ExpiryWorker
import com.privatechat.app.notes.backup.NotesBackup
import com.privatechat.app.notes.data.Category
import com.privatechat.app.notes.data.Note
import com.privatechat.app.notes.data.NotesRepository
import com.privatechat.app.notes.reminder.ReminderManager
import com.privatechat.app.notes.security.NoteLockAuth
import com.privatechat.app.notes.security.NotesLockManager
import com.privatechat.app.notes.util.DocumentScan
import com.privatechat.app.notes.util.GeminiSummarizer
import com.privatechat.app.notes.util.LoadingDialog
import com.privatechat.app.notes.util.QrShare
import com.privatechat.app.notes.util.TextScanner
import com.privatechat.app.ui.login.LoginActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Hello Kitty Notes home — the app's primary screen.
 *
 * Fully isolated Notes experience (Room database kitty_notes.db).
 * The existing chat is reachable only through the three-dot menu's
 * "Enter Promo Code" item, which unlocks the process-local gate and
 * opens the existing LoginActivity unchanged.
 */
class NotesActivity : AppCompatActivity() {

    private val repo by lazy { NotesRepository(this) }

    private lateinit var drawer: DrawerLayout
    private lateinit var notesList: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var chipsList: RecyclerView
    private lateinit var lockView: LinearLayout
    private lateinit var mainContent: LinearLayout
    private lateinit var searchInput: EditText

    private lateinit var adapter: NotesAdapter

    private var categories: List<Category> = emptyList()
    /** null = All, -1 = No Tag, otherwise category id. */
    private var selectedCategoryId: Long? = null
    private var searchQuery: String = ""
    private var listJob: Job? = null
    /** Guards observeData(): re-collecting on every onResume must not
     *  stack duplicate Flow collectors — the previous set is cancelled first. */
    private var observeJob: Job? = null
    /** A text shared from another app while the Notes lock is on. */
    private var pendingSharedText: String? = null
    private var pendingSharedTitle: String? = null
    /** QR parts collected across scans of one multi-part note. */
    private val qrCollected = mutableListOf<String>()
    /** Bulk select: long-press is kept for the actions menu, selection
     *  starts from the overflow menu instead. */
    private var selectionMode: Boolean = false
    private val selectedIds = mutableSetOf<Long>()

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        val progress = LoadingDialog.show(this)
        lifecycleScope.launch {
            runCatching {
                // JSON build + file write run off the UI thread: a big
                // backup used to freeze the home list while it wrote.
                withContext(Dispatchers.IO) {
                    val json = NotesBackup.exportJson(repo.getAllForBackup(), repo.getCategoriesOnce(), repo.getStickiesOnce())
                    contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                }
            }.onSuccess {
                progress.dismiss()
                Toast.makeText(this@NotesActivity, getString(R.string.notes_backup_done), Toast.LENGTH_SHORT).show()
            }.onFailure {
                progress.dismiss()
                Toast.makeText(this@NotesActivity, getString(R.string.notes_backup_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val progress = LoadingDialog.show(this)
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: error("empty")
                    val parsed = NotesBackup.parseJson(text)
                    // Keep note->category grouping by matching category names:
                    // reuse an existing category id, or insert the category
                    // and use its new id. Notes whose name is unknown stay No Tag.
                    val oldIdToName = parsed.categories.associate { it.id to it.name }
                    val nameToId = repo.getCategoriesOnce().associate { it.name to it.id }.toMutableMap()
                    var imported = 0
                    parsed.notes.forEach { n ->
                        val name = n.categoryId?.let { oldIdToName[it] }
                        val mappedId: Long? = when {
                            name == null -> null
                            nameToId.containsKey(name) -> nameToId[name]
                            else -> {
                                val srcCat = parsed.categories.firstOrNull { it.name == name }
                                val newId = repo.addCategory(name)
                                if (srcCat != null && srcCat.isDefault) {
                                    // mark default-ness is cosmetic; id mapping is what matters
                                }
                                nameToId[name] = newId
                                newId
                            }
                        }
                        repo.importNote(n.copy(categoryId = mappedId))
                        imported++
                    }
                    parsed.stickies.forEach { repo.importSticky(it) }
                    imported
                }
            }.onSuccess { count ->
                progress.dismiss()
                Toast.makeText(this@NotesActivity, getString(R.string.notes_restore_done, count), Toast.LENGTH_SHORT).show()
            }.onFailure {
                progress.dismiss()
                Toast.makeText(this@NotesActivity, getString(R.string.notes_restore_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // lateinit: the callback re-launches this launcher for the next QR
    // part, so it cannot be a val initialised with its own callback —
    // the compiler rejects the self-reference ("must be initialized").
    // It is registered in onCreate, before any scan can run.
    private lateinit var qrScanLauncher: ActivityResultLauncher<ScanOptions>

    private val openPdfLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        startActivity(
            Intent(this, PdfViewerActivity::class.java)
                .putExtra(PdfViewerActivity.EXTRA_PDF_URI, uri.toString())
        )
    }

    /**
     * Scan text — the same flow as Scan QR, one tap in the drawer:
     * the document scanner (camera or gallery) captures the page,
     * OCR reads the text out of the photo, and a new note opens
     * with that text and an auto title. No PDF, no gibberish.
     */
    private val scanTextLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val pageUris = DocumentScan.pageUrisFromResult(result.data)
        if (pageUris.isEmpty()) {
            Toast.makeText(this, getString(R.string.notes_scan_failed), Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        scanPagesToNote(pageUris)
    }

    private fun scanPagesToNote(pageUris: List<android.net.Uri>) {
        val progress = AlertDialog.Builder(this)
            .setMessage(getString(R.string.notes_scan_reading))
            .setCancelable(false)
            .create()
        progress.show()
        lifecycleScope.launch {
            val scanned = withContext(Dispatchers.IO) {
                TextScanner.recognizeUris(this@NotesActivity, pageUris)
            }
            scanned.onSuccess { text ->
                // AI cleanup (best-effort, only with a saved key): fixes
                // OCR misreads, keeps wording/case/line breaks and the
                // ** bold markers. Falls back to the raw OCR text.
                var plain = text.plainText
                var md = text.markdownText
                if (GeminiSummarizer.getApiKey(this@NotesActivity) != null) {
                    GeminiSummarizer.cleanOcrText(this@NotesActivity, md).onSuccess { cleaned ->
                        md = cleaned
                        plain = cleaned.replace("**", "")
                    }
                }
                val title = GeminiSummarizer.smartTitle(this@NotesActivity, plain)
                progress.dismiss()
                openEditorWithTemplate(title, md)
                Toast.makeText(
                    this@NotesActivity,
                    getString(R.string.notes_scan_text_saved),
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure {
                progress.dismiss()
                Toast.makeText(
                    this@NotesActivity,
                    getString(R.string.notes_scan_no_text),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Register the QR scanner here (not as a property initializer):
        // its callback re-launches itself for the next part of a
        // multi-part note, and a val cannot reference itself.
        qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
            val contents = result.contents ?: return@registerForActivityResult
            val header = QrShare.parsePartHeader(contents)
            val assembled = QrShare.tryAssemble(contents, qrCollected)
            if (assembled == null) {
                // Multi-part note: keep scanning until every part arrives.
                val total = header?.second ?: 0
                val done = qrCollected.count { it.isNotEmpty() }
                Toast.makeText(
                    this,
                    if (total > 0) getString(R.string.notes_qr_part, done, total)
                    else getString(R.string.notes_qr_scan),
                    Toast.LENGTH_SHORT
                ).show()
                qrScanLauncher.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setOrientationLocked(false)
                        .setPrompt(getString(R.string.notes_qr_scan))
                )
                return@registerForActivityResult
            }
            qrCollected.clear()
            openEditorWithTemplate("", assembled)
            Toast.makeText(this, getString(R.string.notes_qr_received), Toast.LENGTH_SHORT).show()
        }
        // Restore the persisted Notes theme before the first frame is
        // drawn, so Dark Mode survives relaunch without flicker. This
        // only applies the Notes choice; the chat theme preference
        // (Session, applied in App.onCreate) is untouched.
        NotesLockManager.notesDarkMode(this)?.let { dark ->
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                if (dark) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            )
        }
        setContentView(R.layout.activity_notes)

        drawer = findViewById(R.id.notesDrawer)
        notesList = findViewById(R.id.notesList)
        emptyState = findViewById(R.id.notesEmptyState)
        chipsList = findViewById(R.id.notesChips)
        lockView = findViewById(R.id.notesLockView)
        mainContent = findViewById(R.id.notesMainContent)
        searchInput = findViewById(R.id.notesSearchInput)

        adapter = NotesAdapter(
            onClick = { note ->
                if (selectionMode) toggleSelection(note) else openEditor(note.id)
            },
            onLongClick = { note ->
                if (selectionMode) toggleSelection(note) else showNoteActions(note)
            }
        )
        notesList.layoutManager = LinearLayoutManager(this)
        notesList.adapter = adapter
        chipsList.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)

        findViewById<View>(R.id.notesDrawerButton).setOnClickListener { drawer.openDrawer(GravityCompat.START) }
        findViewById<View>(R.id.notesOverflowButton).setOnClickListener { showOverflow(it) }
        findViewById<View>(R.id.notesFab).setOnClickListener { openEditor(0) }
        findViewById<View>(R.id.notesEmptyCreate).setOnClickListener { openEditor(0) }
        findViewById<View>(R.id.notesSearchButton).setOnClickListener {
            val bar = findViewById<View>(R.id.notesSearchBar)
            bar.visibility = if (bar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (bar.visibility == View.VISIBLE) searchInput.requestFocus()
        }
        findViewById<View>(R.id.notesCloudButton).setOnClickListener { showSyncStatus() }
        findViewById<View>(R.id.notesSearchClear).setOnClickListener {
            searchInput.text.clear()
            searchQuery = ""
            refreshList()
        }
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchQuery = s?.toString().orEmpty()
                refreshList()
            }
        })

        setupDrawer()
        setupLock()
        setupSelectionBar()

        // Smart pack: daily auto-backup + handle a text shared from
        // another app (browser, YouTube, …) into a new note.
        BackupWorker.scheduleDaily(this)
        // Study pack: daily self-destruct sweep (expired notes to Trash).
        ExpiryWorker.scheduleDaily(this)
        handleShareIntent()

        // Kitty Notes Plus housekeeping (Notes data only): permanently
        // remove Trash entries older than 30 days, and re-arm any note
        // reminders (covers reboot/restore/timezone changes).
        lifecycleScope.launch {
            runCatching {
                repo.autoCleanTrash()
                // Self-destruct check on every launch, so an expired
                // note never waits for the daily worker to run.
                repo.moveExpiredNotesToTrash().forEach { ReminderManager.cancel(this@NotesActivity, it.id) }
                ReminderManager.ensureChannel(this@NotesActivity)
                ReminderManager.rescheduleAll(this@NotesActivity)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { repo.ensureDefaultCategories() }
        observeData()
        applyAccent()
    }

    /** Applies the chosen Notes theme accent to real UI elements (FAB tint). */
    private fun applyAccent() {
        val hex = when (NotesLockManager.getAccent(this)) {
            "lavender" -> "#7E57C2"
            "mint" -> "#43A047"
            "sky" -> "#1E88E5"
            else -> "#EC407A"
        }
        findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.notesFab)
            .backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(hex))
    }

    // ---------------- lock ----------------

    private fun setupLock() {
        val mustLock = NotesLockManager.isLockEnabled(this) && !NotesLockManager.unlockedThisProcess
        lockView.visibility = if (mustLock) View.VISIBLE else View.GONE
        mainContent.visibility = if (mustLock) View.GONE else View.VISIBLE
        // The pen FAB is an editing control: never visible before unlock.
        // Set synchronously here in onCreate, before the first frame draws,
        // so it cannot flash on launch or recreation.
        findViewById<View>(R.id.notesFab).visibility = if (mustLock) View.GONE else View.VISIBLE
        if (!mustLock) return

        val input = findViewById<EditText>(R.id.lockPasswordInput)
        val error = findViewById<TextView>(R.id.lockError)
        findViewById<View>(R.id.lockUnlockButton).setOnClickListener {
            val pw = input.text.toString()
            // PBKDF2 hashing takes ~100-300ms: keep the tap responsive by
            // verifying off the UI thread, then applying the same UI result.
            // The loading dialog shows while it verifies, so the button
            // never looks dead after the tap.
            val progress = LoadingDialog.show(this@NotesActivity)
            lifecycleScope.launch {
                if (NotesLockManager.verify(this@NotesActivity, pw)) {
                    progress.dismiss()
                    NotesLockManager.unlockedThisProcess = true
                    lockView.visibility = View.GONE
                    mainContent.visibility = View.VISIBLE
                    findViewById<View>(R.id.notesFab).visibility = View.VISIBLE
                    error.visibility = View.GONE
                    openPendingShare()
                } else {
                    progress.dismiss()
                    error.visibility = View.VISIBLE
                    input.text.clear()
                }
            }
        }
        setupFingerprintUnlock(error)
    }

    /**
     * Additive fingerprint unlock (framework BiometricPrompt, API 28+).
     * Shown only when the device has fingerprint hardware with an
     * enrolled fingerprint; the Notes password always remains as the
     * fallback and is verified by the unchanged password path.
     */
    private fun setupFingerprintUnlock(error: TextView) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val button = findViewById<View>(R.id.lockFingerprintButton)
        val available = runCatching {
            @Suppress("DEPRECATION")
            androidx.core.hardware.fingerprint.FingerprintManagerCompat.from(this)
                .let { it.isHardwareDetected && it.hasEnrolledFingerprints() }
        }.getOrDefault(false)
        if (!available) return
        button.visibility = View.VISIBLE
        button.setOnClickListener {
            val executor = ContextCompat.getMainExecutor(this)
            val prompt = BiometricPrompt.Builder(this)
                .setTitle(getString(R.string.notes_fingerprint_prompt))
                .setSubtitle(getString(R.string.notes_fingerprint_subtitle))
                .setNegativeButton(getString(android.R.string.cancel), executor) { _, _ -> }
                .build()
            prompt.authenticate(
                android.os.CancellationSignal(),
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        NotesLockManager.unlockedThisProcess = true
                        lockView.visibility = View.GONE
                        mainContent.visibility = View.VISIBLE
                        findViewById<View>(R.id.notesFab).visibility = View.VISIBLE
                        error.visibility = View.GONE
                        openPendingShare()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        // Cancel/lockout etc.: the system prompt already
                        // explained it; the password path stays as-is.
                    }

                    override fun onAuthenticationFailed() {
                        error.text = getString(R.string.notes_fingerprint_failed)
                        error.visibility = View.VISIBLE
                    }
                }
            )
        }
    }

    // ---------------- drawer ----------------

    private fun setupDrawer() {
        val nav = findViewById<NavigationView>(R.id.notesNavView)
        nav.setNavigationItemSelectedListener { item ->
            drawer.closeDrawers()
            when (item.itemId) {
                R.id.nav_sync -> { showSyncStatus(); true }
                R.id.nav_backup -> { exportLauncher.launch("kitty-notes-backup.json"); true }
                R.id.nav_restore -> { importLauncher.launch(arrayOf("application/json")); true }
                R.id.nav_calendar -> { startActivity(Intent(this, CalendarActivity::class.java)); true }
                R.id.nav_templates -> { startActivity(Intent(this, TemplatesActivity::class.java)); true }
                R.id.nav_quick_note -> { showQuickNote(); true }
                R.id.nav_sticky -> { startActivity(Intent(this, StickyNotesActivity::class.java)); true }
                R.id.nav_note_tool -> { startActivity(Intent(this, NoteToolActivity::class.java)); true }
                R.id.nav_settings -> { startActivity(Intent(this, NotesSettingsActivity::class.java)); true }
                R.id.nav_archive -> { startActivity(SimpleNotesActivity.intent(this, SimpleNotesActivity.MODE_ARCHIVE)); true }
                R.id.nav_trash -> { startActivity(SimpleNotesActivity.intent(this, SimpleNotesActivity.MODE_TRASH)); true }
                R.id.nav_habit -> { startActivity(Intent(this, HabitActivity::class.java)); true }
                R.id.nav_qr_scan -> {
                    qrCollected.clear()
                    qrScanLauncher.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setOrientationLocked(false)
                            .setPrompt(getString(R.string.notes_qr_scan))
                    )
                    true
                }
                R.id.nav_scan_text -> {
                    DocumentScan.launch(
                        this,
                        DocumentScan.createScanner(this),
                        scanTextLauncher
                    ) {
                        Toast.makeText(this, getString(R.string.notes_scan_failed), Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                R.id.nav_open_pdf -> { openPdfLauncher.launch(arrayOf("application/pdf")); true }
                R.id.nav_ai_write -> { promptAiWrite(); true }
                R.id.nav_study_helper -> { promptStudyMaterial(); true }
                R.id.nav_backup_now -> { runBackupNow(); true }
                else -> false
            }
        }
    }

    private fun showSyncStatus() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.notes_sync_title))
            .setMessage(getString(R.string.notes_sync_unavailable))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showQuickNote() {
        val input = EditText(this).apply {
            hint = getString(R.string.notes_quick_hint)
            minLines = 3
            gravity = Gravity.TOP
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(input)
        }
        var saving = false
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.notes_quick_title))
            .setView(container)
            .setPositiveButton(getString(R.string.notes_save)) { _, _ ->
                if (saving) return@setPositiveButton
                saving = true
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    lifecycleScope.launch {
                        repo.saveNote(Note(title = text.lineSequence().firstOrNull().orEmpty().take(60), content = text))
                        Toast.makeText(this@NotesActivity, getString(R.string.notes_saved), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------- overflow / promo ----------------

    private fun showOverflow(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.apply {
            add(0, 1, 0, getString(R.string.menu_enter_promo_code))
            add(0, 2, 1, getString(R.string.notes_settings_title))
            add(0, 3, 2, getString(R.string.notes_about_title))
            add(0, 4, 3, getString(R.string.notes_privacy_title))
            add(0, 5, 4, getString(R.string.notes_sort_title))
            add(0, 6, 5, getString(R.string.notes_select_title))
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { showPromoDialog(); true }
                2 -> { startActivity(Intent(this, NotesSettingsActivity::class.java)); true }
                3 -> { startActivity(Intent(this, AboutActivity::class.java)); true }
                4 -> { showPrivacy(); true }
                5 -> { showSortDialog(); true }
                6 -> { enterSelectionMode(); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun showSortDialog() {
        val labels = arrayOf(
            getString(R.string.notes_sort_recent),
            getString(R.string.notes_sort_newest),
            getString(R.string.notes_sort_oldest),
            getString(R.string.notes_sort_title_az)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.notes_sort_title))
            .setSingleChoiceItems(labels, NotesLockManager.getSortMode(this)) { dialog, which ->
                NotesLockManager.setSortMode(this@NotesActivity, which)
                refreshList()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Applies the chosen sort order; mode 0 keeps the original order. */
    private fun sortNotes(list: List<Note>): List<Note> {
        return when (NotesLockManager.getSortMode(this)) {
            1 -> list.sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.createdAt })
            2 -> list.sortedWith(compareByDescending<Note> { it.pinned }.thenBy { it.createdAt })
            3 -> list.sortedWith(compareByDescending<Note> { it.pinned }.thenBy { it.title.lowercase() })
            else -> list
        }
    }

    private fun showPrivacy() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.notes_privacy_title))
            .setMessage(getString(R.string.notes_privacy_body))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showPromoDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.promo_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val error = TextView(this).apply {
            text = getString(R.string.promo_error)
            setTextColor(getColor(R.color.kittyError))
            visibility = View.GONE
            setPadding(0, 16, 0, 0)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 32, 56, 8)
            addView(input)
            addView(error)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.menu_enter_promo_code))
            .setMessage(getString(R.string.promo_subtitle))
            .setView(container)
            .setPositiveButton(getString(R.string.promo_continue), null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val entered = input.text.toString().trim()
                        if (entered == AccessGate.REQUIRED_CODE) {
                            AccessGate.unlock()
                            dismiss()
                            startActivity(Intent(this@NotesActivity, LoginActivity::class.java))
                        } else {
                            error.visibility = View.VISIBLE
                            input.text.clear()
                        }
                    }
                }
                show()
            }
    }

    // ---------------- data ----------------

    private fun observeData() {
        // onResume() runs on every return to this screen; without this
        // cancel, each resume would add 4 more live collectors doing
        // identical rendering work (wasted CPU/battery, flicker risk).
        observeJob?.cancel()
        observeJob = lifecycleScope.launch {
            launch {
                repo.observeCategories().collectLatest { cats ->
                    categories = cats
                    renderChips()
                    refreshList()
                }
            }
            launch {
                repo.observeArchivedCount().collectLatest { count ->
                    updateDrawerCount(R.id.nav_archive, getString(R.string.notes_archive_title), count)
                }
            }
            launch {
                repo.observeTrashCount().collectLatest { count ->
                    updateDrawerCount(R.id.nav_trash, getString(R.string.notes_trash_title), count)
                }
            }
            launch {
                repo.observeImageCounts().collectLatest { counts ->
                    adapter.imageCounts = counts.associate { it.noteId to it.cnt }
                    adapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun updateDrawerCount(itemId: Int, base: String, count: Int) {
        val nav = findViewById<NavigationView>(R.id.notesNavView)
        val item = nav.menu.findItem(itemId) ?: return
        item.title = if (count > 0) "$base ($count)" else base
    }

    private fun renderChips() {
        val chips = mutableListOf<Pair<String, Long?>>()
        chips.add(getString(R.string.notes_chip_all) to null)
        categories.forEach { chips.add(it.name to it.id) }
        chips.add(getString(R.string.notes_no_tag) to -1L)
        chips.add("+ " + getString(R.string.notes_chip_new_category) to -2L)

        val ctx = this
        chipsList.adapter = object : RecyclerView.Adapter<ChipVH>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ChipVH {
                val tv = layoutInflater.inflate(R.layout.item_chip, parent, false) as TextView
                return ChipVH(tv)
            }
            override fun getItemCount() = chips.size
            override fun onBindViewHolder(holder: ChipVH, position: Int) {
                val (label, id) = chips[position]
                val tv = holder.text
                tv.text = label
                val selected = when {
                    id == -2L -> false
                    id == -1L -> selectedCategoryId == -1L
                    id == null -> selectedCategoryId == null
                    else -> selectedCategoryId == id
                }
                tv.setBackgroundResource(if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
                tv.setTextColor(getColor(if (selected) R.color.kittyOnPrimary else R.color.textPrimary))
                tv.setOnClickListener {
                    when (id) {
                        -2L -> showCategoryDialog(null)
                        else -> {
                            selectedCategoryId = id
                            notifyDataSetChanged()
                            refreshList()
                        }
                    }
                }
                tv.setOnLongClickListener {
                    val cat = categories.firstOrNull { it.id == id }
                    if (cat != null) showCategoryDialog(cat)
                    true
                }
            }
        }
    }

    class ChipVH(val text: TextView) : RecyclerView.ViewHolder(text)

    private fun showCategoryDialog(existing: Category?) {
        val input = EditText(this).apply {
            hint = getString(R.string.notes_category_name_hint)
            setText(existing?.name.orEmpty())
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 24, 56, 8)
            addView(input)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) getString(R.string.notes_add_category) else getString(R.string.notes_edit_category))
            .setView(container)
            .setPositiveButton(getString(R.string.notes_save)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    if (existing == null) repo.addCategory(name) else repo.renameCategory(existing, name)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (existing != null) {
            builder.setNeutralButton(getString(R.string.notes_delete_category)) { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.notes_delete_category_title))
                    .setMessage(getString(R.string.notes_delete_category_msg))
                    .setPositiveButton(getString(R.string.notes_delete)) { _, _ ->
                        lifecycleScope.launch {
                            repo.removeCategory(existing)
                            if (selectedCategoryId == existing.id) selectedCategoryId = null
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        builder.show()
    }

    private fun refreshList() {
        listJob?.cancel()
        val flow = when {
            searchQuery.isNotBlank() -> repo.searchActive(searchQuery)
            selectedCategoryId == null -> repo.observeActive()
            selectedCategoryId == -1L -> repo.observeActiveNoTag()
            else -> repo.observeActiveByCategory(selectedCategoryId!!)
        }
        listJob = lifecycleScope.launch {
            flow.collectLatest { list ->
                adapter.submitList(sortNotes(list))
                val showEmpty = list.isEmpty() && searchQuery.isBlank()
                emptyState.visibility = if (showEmpty) View.VISIBLE else View.GONE
                notesList.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun showNoteActions(note: Note) {
        val labels = arrayOf(
            if (note.pinned) getString(R.string.notes_unpin) else getString(R.string.notes_pin),
            getString(R.string.notes_archive_action),
            getString(R.string.notes_share_note),
            getString(R.string.notes_delete),
            getString(R.string.notes_duplicate),
            if (note.locked) getString(R.string.notes_unlock_note) else getString(R.string.notes_lock_note)
        )
        AlertDialog.Builder(this)
            .setTitle(note.title.ifBlank { getString(R.string.notes_untitled) })
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> lifecycleScope.launch { repo.setPinned(note, !note.pinned) }
                    1 -> lifecycleScope.launch {
                        repo.setArchived(note, true)
                        ReminderManager.cancel(this@NotesActivity, note.id)
                    }
                    2 -> shareNote(note)
                    3 -> lifecycleScope.launch {
                        repo.moveToTrash(note)
                        ReminderManager.cancel(this@NotesActivity, note.id)
                    }
                    4 -> lifecycleScope.launch {
                        repo.duplicateNote(note)
                        Toast.makeText(this@NotesActivity, getString(R.string.notes_duplicated), Toast.LENGTH_SHORT).show()
                    }
                    5 -> toggleNoteLock(note)
                }
            }
            .show()
    }

    // ---------------- bulk select ----------------

    private fun setupSelectionBar() {
        findViewById<View>(R.id.notesSelectionClose).setOnClickListener { exitSelectionMode() }
        findViewById<View>(R.id.notesSelectionDelete).setOnClickListener { bulkDelete() }
        findViewById<View>(R.id.notesSelectionArchive).setOnClickListener { bulkArchive() }
        findViewById<View>(R.id.notesSelectionCategory).setOnClickListener { bulkCategoryDialog() }
    }

    private fun enterSelectionMode() {
        selectionMode = true
        selectedIds.clear()
        updateSelectionBar()
    }

    private fun exitSelectionMode() {
        if (!selectionMode) return
        selectionMode = false
        selectedIds.clear()
        updateSelectionBar()
    }

    private fun toggleSelection(note: Note) {
        if (selectedIds.contains(note.id)) selectedIds.remove(note.id) else selectedIds.add(note.id)
        updateSelectionBar()
    }

    private fun updateSelectionBar() {
        findViewById<View>(R.id.notesSelectionBar).visibility =
            if (selectionMode) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.notesSelectionCount).text =
            getString(R.string.notes_selected_count, selectedIds.size)
        adapter.selectionMode = selectionMode
        adapter.selectedIds = selectedIds.toSet()
        adapter.notifyDataSetChanged()
    }

    private fun selectedNotes(): List<Note> =
        adapter.currentList.filter { selectedIds.contains(it.id) }

    private fun bulkDelete() {
        val notes = selectedNotes()
        if (notes.isEmpty()) {
            exitSelectionMode()
            return
        }
        lifecycleScope.launch {
            notes.forEach {
                repo.moveToTrash(it)
                ReminderManager.cancel(this@NotesActivity, it.id)
            }
            exitSelectionMode()
            Toast.makeText(this@NotesActivity, getString(R.string.notes_bulk_deleted, notes.size), Toast.LENGTH_SHORT).show()
        }
    }

    private fun bulkArchive() {
        val notes = selectedNotes()
        if (notes.isEmpty()) {
            exitSelectionMode()
            return
        }
        lifecycleScope.launch {
            notes.forEach {
                repo.setArchived(it, true)
                ReminderManager.cancel(this@NotesActivity, it.id)
            }
            exitSelectionMode()
            Toast.makeText(this@NotesActivity, getString(R.string.notes_bulk_archived, notes.size), Toast.LENGTH_SHORT).show()
        }
    }

    private fun bulkCategoryDialog() {
        val notes = selectedNotes()
        if (notes.isEmpty()) {
            exitSelectionMode()
            return
        }
        lifecycleScope.launch {
            val cats = repo.getCategoriesOnce()
            val labels = arrayOf(getString(R.string.notes_no_category)) +
                cats.map { it.name }.toTypedArray()
            AlertDialog.Builder(this@NotesActivity)
                .setTitle(getString(R.string.notes_bulk_category))
                .setItems(labels) { _, which ->
                    val categoryId = if (which == 0) null else cats[which - 1].id
                    lifecycleScope.launch {
                        notes.forEach { repo.saveNote(it.copy(categoryId = categoryId)) }
                        exitSelectionMode()
                        Toast.makeText(this@NotesActivity, getString(R.string.notes_bulk_categorized, notes.size), Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** Per-note lock toggle from the home list. */
    private fun toggleNoteLock(note: Note) {
        if (note.locked) {
            lifecycleScope.launch {
                repo.setLocked(note, false)
                NoteLockAuth.markLocked(note.id)
                Toast.makeText(this@NotesActivity, getString(R.string.notes_note_unlocked_toast), Toast.LENGTH_SHORT).show()
            }
            return
        }
        NoteLockAuth.ensurePassword(this) {
            lifecycleScope.launch {
                repo.setLocked(note, true)
                Toast.makeText(this@NotesActivity, getString(R.string.notes_note_locked_toast), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareNote(note: Note) {
        val text = if (note.title.isBlank()) note.content else "${note.title}\n\n${note.content}"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, note.title)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.notes_share_note)))
    }

    /** A text shared from another app becomes a new note. */
    private fun handleShareIntent() {
        if (intent?.action != Intent.ACTION_SEND) return
        if (intent.type != "text/plain") return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        if (NotesLockManager.isLockEnabled(this) && !NotesLockManager.unlockedThisProcess) {
            // Locked: hold it until the user unlocks, so a share-in
            // never bypasses the Notes lock.
            pendingSharedText = text
            pendingSharedTitle = subject
            return
        }
        openEditorWithTemplate(subject, text)
    }

    private fun openPendingShare() {
        val text = pendingSharedText ?: return
        pendingSharedText = null
        val title = pendingSharedTitle
        pendingSharedTitle = null
        openEditorWithTemplate(title, text)
    }

    /**
     * Button row shared by the AI dialogs: Cancel | বাংলা/English | OK.
     * The language is pre-selected from the last AI choice and remembered
     * for next time (shared with AI summary).
     */
    private class LangRow(
        val row: LinearLayout,
        val langs: RadioGroup,
        val bnButton: RadioButton,
        val enButton: RadioButton,
        val cancel: TextView,
        val ok: TextView
    )

    private fun makeLangButtonRow(): LangRow {
        val dp = resources.displayMetrics.density
        // Button row: Cancel | বাংলা/English | OK.
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (24 * dp).toInt(), 0, 0)
        }
        val styled = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        // Two separate instances: one shared drawable would carry the
        // pressed state from one button to the other.
        val cancelRipple = styled.getDrawable(0)
        val okRipple = styled.getDrawable(0)
        styled.recycle()
        val primary = ContextCompat.getColor(this, R.color.primary)
        val buttonPaddingH = (16 * dp).toInt()
        val buttonPaddingV = (12 * dp).toInt()
        val cancel = TextView(this).apply {
            text = getString(android.R.string.cancel)
            setTextColor(primary)
            textSize = 15f
            setPadding(buttonPaddingH, buttonPaddingV, buttonPaddingH, buttonPaddingV)
            background = cancelRipple
        }
        val langs = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val bnButton = RadioButton(this).apply {
            id = View.generateViewId()
            text = "বাংলা"
        }
        val enButton = RadioButton(this).apply {
            id = View.generateViewId()
            text = "English"
        }
        langs.addView(bnButton)
        langs.addView(enButton)
        langs.check(if (GeminiSummarizer.getSummaryLang(this) == "en") enButton.id else bnButton.id)
        val ok = TextView(this).apply {
            text = getString(android.R.string.ok)
            setTextColor(primary)
            textSize = 15f
            setPadding(buttonPaddingH, buttonPaddingV, buttonPaddingH, buttonPaddingV)
            background = okRipple
        }
        buttonRow.addView(cancel)
        buttonRow.addView(langs)
        buttonRow.addView(ok)
        return LangRow(buttonRow, langs, bnButton, enButton, cancel, ok)
    }

    /** Language picked in a [LangRow]: "bn" or "en". */
    private fun LangRow.pickedLang(): String =
        if (langs.checkedRadioButtonId == enButton.id) "en" else "bn"

    /**
     * Write with AI: one line of idea in, a whole note out. The
     * generated title + body open in the editor — nothing is saved
     * silently.
     */
    private fun promptAiWrite() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        val input = EditText(this).apply {
            hint = getString(R.string.notes_ai_write_hint)
        }
        val langRow = makeLangButtonRow()
        container.addView(input)
        container.addView(langRow.row)
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.notes_ai_write))
            .setView(container)
            .create()
        langRow.cancel.setOnClickListener { dialog.dismiss() }
        langRow.ok.setOnClickListener {
            val idea = input.text.toString().trim()
            if (idea.isBlank()) {
                Toast.makeText(this, getString(R.string.notes_ai_too_short), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val lang = langRow.pickedLang()
            GeminiSummarizer.saveSummaryLang(this, lang)
            dialog.dismiss()
            runAiWrite(idea, lang)
        }
        dialog.show()
    }

    /** Study helper type keys, in the same order as R.array.study_types. */
    private val STUDY_TYPE_KEYS = listOf(
        "PARAGRAPH", "LETTER", "EMAIL", "DIALOGUE",
        "STORY", "COMPOSITION", "SUMMARY", "BHAB"
    )

    /**
     * Study helper: a topic + a type (paragraph, letter, email,
     * dialogue, story, composition, summary, ভাবসম্প্রসারণ) + a
     * language (বাংলা/English) in, a school-textbook-style answer out.
     * The generated note opens in the editor — nothing is saved silently.
     */
    private fun promptStudyMaterial() {
        val dp = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        val input = EditText(this).apply {
            hint = getString(R.string.notes_study_hint)
        }
        val typeLabel = TextView(this).apply {
            text = getString(R.string.notes_study_type)
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@NotesActivity, R.color.textSecondary))
            setPadding(0, (16 * dp).toInt(), 0, (4 * dp).toInt())
        }
        val typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@NotesActivity,
                android.R.layout.simple_spinner_item,
                resources.getStringArray(R.array.study_types)
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        val langRow = makeLangButtonRow()
        container.addView(input)
        container.addView(typeLabel)
        container.addView(typeSpinner)
        container.addView(langRow.row)
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.notes_study_helper))
            .setView(container)
            .create()
        langRow.cancel.setOnClickListener { dialog.dismiss() }
        langRow.ok.setOnClickListener {
            val topic = input.text.toString().trim()
            if (topic.isBlank()) {
                Toast.makeText(this, getString(R.string.notes_ai_too_short), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val lang = langRow.pickedLang()
            GeminiSummarizer.saveSummaryLang(this, lang)
            val typeKey = STUDY_TYPE_KEYS.getOrElse(typeSpinner.selectedItemPosition) { "PARAGRAPH" }
            dialog.dismiss()
            runStudyMaterial(topic, typeKey, lang)
        }
        dialog.show()
    }

    /**
     * AI loading dialog: a pulsing ✨ and an "AI লিখছে..." label whose
     * dots cycle while Gemini writes. The animation stops itself when
     * the dialog is dismissed, so nothing keeps running afterwards.
     */
    private fun showAiLoadingDialog(titleRes: Int): AlertDialog {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 40, 48, 40)
        }
        val sparkle = TextView(this).apply {
            text = "✨"
            textSize = 36f
            gravity = Gravity.CENTER
        }
        val label = TextView(this).apply {
            text = getString(R.string.notes_ai_writing)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        container.addView(sparkle)
        container.addView(label)
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(titleRes))
            .setView(container)
            .setCancelable(false)
            .create()
        val pulse = ObjectAnimator.ofFloat(sparkle, "alpha", 1f, 0.25f, 1f).apply {
            duration = 1200
            repeatCount = ObjectAnimator.INFINITE
        }
        val dots = arrayOf("", ".", "..", "...")
        var dotIndex = 0
        val handler = Handler(Looper.getMainLooper())
        val dotTask = object : Runnable {
            override fun run() {
                label.text = getString(R.string.notes_ai_writing) + dots[dotIndex % dots.size]
                dotIndex++
                handler.postDelayed(this, 400)
            }
        }
        dialog.setOnShowListener { pulse.start(); handler.post(dotTask) }
        dialog.setOnDismissListener { pulse.cancel(); handler.removeCallbacks(dotTask) }
        return dialog
    }

    /** No API key yet: point at Settings (same for every AI feature). */
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

    private fun runAiWrite(idea: String, lang: String) {
        if (GeminiSummarizer.getApiKey(this) == null) {
            showAiNoKeyDialog(R.string.notes_ai_write)
            return
        }
        val loading = showAiLoadingDialog(R.string.notes_ai_write)
        loading.show()
        lifecycleScope.launch {
            val result = GeminiSummarizer.writeFromIdea(
                this@NotesActivity, idea, lang
            )
            loading.dismiss()
            result.onSuccess { (title, body) ->
                openEditorWithTemplate(title, body)
                Toast.makeText(this@NotesActivity, getString(R.string.notes_ai_write_done), Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@NotesActivity, getString(R.string.notes_ai_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun runStudyMaterial(topic: String, typeKey: String, lang: String) {
        if (GeminiSummarizer.getApiKey(this) == null) {
            showAiNoKeyDialog(R.string.notes_study_helper)
            return
        }
        val loading = showAiLoadingDialog(R.string.notes_study_helper)
        loading.show()
        lifecycleScope.launch {
            val result = GeminiSummarizer.writeStudyMaterial(
                this@NotesActivity, topic, typeKey, lang
            )
            loading.dismiss()
            result.onSuccess { (title, body) ->
                openEditorWithTemplate(title, body)
                Toast.makeText(this@NotesActivity, getString(R.string.notes_study_done), Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@NotesActivity, getString(R.string.notes_ai_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openEditorWithTemplate(title: String?, content: String) {
        val editorIntent = Intent(this, NoteEditorActivity::class.java)
            .putExtra(NoteEditorActivity.EXTRA_TEMPLATE_CONTENT, content)
        if (!title.isNullOrBlank()) {
            editorIntent.putExtra(NoteEditorActivity.EXTRA_TEMPLATE_TITLE, title)
        }
        startActivity(editorIntent)
    }

    /** Runs one auto-backup right now (same file format as the daily one). */
    private fun runBackupNow() {
        val progress = LoadingDialog.show(this)
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { BackupWorker.doBackup(this@NotesActivity) }
            progress.dismiss()
            Toast.makeText(
                this@NotesActivity,
                getString(if (ok) R.string.notes_backup_done else R.string.notes_backup_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun openEditor(noteId: Long) {
        if (noteId == 0L) {
            exitSelectionMode()
            startEditor(0)
            return
        }
        exitSelectionMode()
        lifecycleScope.launch {
            val note = repo.getNote(noteId)
            if (note?.locked == true && !NoteLockAuth.isUnlocked(noteId)) {
                // Locked note: fingerprint or Notes password first.
                NoteLockAuth.promptUnlock(this@NotesActivity) {
                    NoteLockAuth.markUnlocked(noteId)
                    startEditor(noteId)
                }
            } else {
                startEditor(noteId)
            }
        }
    }

    private fun startEditor(noteId: Long) {
        startActivity(Intent(this, NoteEditorActivity::class.java).putExtra(NoteEditorActivity.EXTRA_NOTE_ID, noteId))
    }

    override fun onBackPressed() {
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START)
        } else if (selectionMode) {
            exitSelectionMode()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
