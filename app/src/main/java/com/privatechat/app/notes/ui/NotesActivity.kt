package com.privatechat.app.notes.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.navigation.NavigationView
import com.privatechat.app.R
import com.privatechat.app.data.AccessGate
import com.privatechat.app.notes.backup.NotesBackup
import com.privatechat.app.notes.data.Category
import com.privatechat.app.notes.data.Note
import com.privatechat.app.notes.data.NotesRepository
import com.privatechat.app.notes.security.NotesLockManager
import com.privatechat.app.ui.login.LoginActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            runCatching {
                val json = NotesBackup.exportJson(repo.getAllForBackup(), repo.getCategoriesOnce(), repo.getStickiesOnce())
                contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            }.onSuccess {
                Toast.makeText(this@NotesActivity, getString(R.string.notes_backup_done), Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@NotesActivity, getString(R.string.notes_backup_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            runCatching {
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
            }.onSuccess { count ->
                Toast.makeText(this@NotesActivity, getString(R.string.notes_restore_done, count), Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@NotesActivity, getString(R.string.notes_restore_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notes)

        drawer = findViewById(R.id.notesDrawer)
        notesList = findViewById(R.id.notesList)
        emptyState = findViewById(R.id.notesEmptyState)
        chipsList = findViewById(R.id.notesChips)
        lockView = findViewById(R.id.notesLockView)
        mainContent = findViewById(R.id.notesMainContent)
        searchInput = findViewById(R.id.notesSearchInput)

        adapter = NotesAdapter(
            onClick = { note -> openEditor(note.id) },
            onLongClick = { note -> showNoteActions(note) }
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
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { repo.ensureDefaultCategories() }
        observeData()
    }

    // ---------------- lock ----------------

    private fun setupLock() {
        val mustLock = NotesLockManager.isLockEnabled(this) && !NotesLockManager.unlockedThisProcess
        lockView.visibility = if (mustLock) View.VISIBLE else View.GONE
        mainContent.visibility = if (mustLock) View.GONE else View.VISIBLE
        if (!mustLock) return

        val input = findViewById<EditText>(R.id.lockPasswordInput)
        val error = findViewById<TextView>(R.id.lockError)
        findViewById<View>(R.id.lockUnlockButton).setOnClickListener {
            val pw = input.text.toString()
            if (NotesLockManager.verify(this, pw)) {
                NotesLockManager.unlockedThisProcess = true
                lockView.visibility = View.GONE
                mainContent.visibility = View.VISIBLE
                error.visibility = View.GONE
            } else {
                error.visibility = View.VISIBLE
                input.text.clear()
            }
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
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { showPromoDialog(); true }
                2 -> { startActivity(Intent(this, NotesSettingsActivity::class.java)); true }
                3 -> { startActivity(Intent(this, AboutActivity::class.java)); true }
                4 -> { showPrivacy(); true }
                else -> false
            }
        }
        popup.show()
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
        lifecycleScope.launch {
            repo.observeCategories().collectLatest { cats ->
                categories = cats
                renderChips()
                refreshList()
            }
        }
        lifecycleScope.launch {
            repo.observeArchivedCount().collectLatest { count ->
                updateDrawerCount(R.id.nav_archive, getString(R.string.notes_archive_title), count)
            }
        }
        lifecycleScope.launch {
            repo.observeTrashCount().collectLatest { count ->
                updateDrawerCount(R.id.nav_trash, getString(R.string.notes_trash_title), count)
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
                adapter.submitList(list)
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
            getString(R.string.notes_delete)
        )
        AlertDialog.Builder(this)
            .setTitle(note.title.ifBlank { getString(R.string.notes_untitled) })
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> lifecycleScope.launch { repo.setPinned(note, !note.pinned) }
                    1 -> lifecycleScope.launch { repo.setArchived(note, true) }
                    2 -> shareNote(note)
                    3 -> lifecycleScope.launch { repo.moveToTrash(note) }
                }
            }
            .show()
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

    private fun openEditor(noteId: Long) {
        startActivity(Intent(this, NoteEditorActivity::class.java).putExtra(NoteEditorActivity.EXTRA_NOTE_ID, noteId))
    }

    override fun onBackPressed() {
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
