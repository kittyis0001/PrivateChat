package com.privatechat.app.notes.ui

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.privatechat.app.R
import com.privatechat.app.notes.data.AiChat
import com.privatechat.app.notes.data.NotesDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Saved AI chats: every conversation is kept on this device, so a
 * chat can be reopened and continued later. Long-press a chat to
 * rename or delete it.
 */
class AiChatListActivity : AppCompatActivity() {

    private lateinit var adapter: AiChatListAdapter
    private lateinit var emptyView: TextView
    private val chats = mutableListOf<Pair<AiChat, String?>>()
    private val dao by lazy { NotesDatabase.get(this).aiChatDao() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_chat_list)
        val toolbar = findViewById<Toolbar>(R.id.aiChatListToolbar)
        toolbar.title = getString(R.string.notes_ai_chat_list_title)
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = AiChatListAdapter(
            chats,
            onOpen = { chat -> openChat(chat.id) },
            onMore = { chat -> showChatOptions(chat) }
        )
        val list = findViewById<RecyclerView>(R.id.aiChatListView)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        emptyView = findViewById(R.id.aiChatListEmpty)
        findViewById<Button>(R.id.aiChatListNew).setOnClickListener {
            startActivity(Intent(this, AiChatActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        loadChats()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun loadChats() {
        lifecycleScope.launch {
            val withPreview = withContext(Dispatchers.IO) {
                dao.getAllChats().map { it to dao.getLastMessageText(it.id) }
            }
            chats.clear()
            chats.addAll(withPreview)
            adapter.notifyDataSetChanged()
            emptyView.visibility = if (chats.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun openChat(id: Long) {
        startActivity(
            Intent(this, AiChatActivity::class.java)
                .putExtra(AiChatActivity.EXTRA_CHAT_ID, id)
        )
    }

    private fun showChatOptions(chat: AiChat) {
        AlertDialog.Builder(this)
            .setTitle(chat.title)
            .setItems(
                arrayOf(
                    getString(R.string.notes_ai_chat_rename),
                    getString(R.string.notes_ai_chat_delete)
                )
            ) { _, which ->
                if (which == 0) askRename(chat) else askDelete(chat)
            }
            .show()
    }

    private fun askRename(chat: AiChat) {
        val input = EditText(this).apply {
            setText(chat.title)
            setSelection(chat.title.length)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.notes_ai_chat_rename))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val title = input.text.toString().trim()
                if (title.isBlank()) return@setPositiveButton
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { dao.renameChat(chat.id, title) }
                    loadChats()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askDelete(chat: AiChat) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.notes_ai_chat_delete_confirm))
            .setPositiveButton(getString(R.string.notes_ai_chat_delete)) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        dao.deleteMessages(chat.id)
                        dao.deleteChat(chat.id)
                    }
                    loadChats()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
