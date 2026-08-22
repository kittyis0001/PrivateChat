package com.privatechat.app.ui.chat

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.lifecycleScope
import com.privatechat.app.data.Session
import com.privatechat.app.data.model.Message
import com.privatechat.app.data.repository.ChatRepository
import com.privatechat.app.databinding.ActivityChatBinding
import com.privatechat.app.utils.PresenceFormatter

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var repository: ChatRepository
    private lateinit var adapter: MessageAdapter

    private val messages = mutableListOf<Message>()
    private var typingHandler: Handler? = null
    private var typingRunnable: Runnable? = null

    // The message currently selected via swipe/menu "Reply", if any. Null
    // means the compose bar is in its normal (non-reply) state.
    private var replyingTo: Message? = null

    // The one AlertDialog that can be open at a time (edit message / emoji
    // picker) — the reaction bar and action menu are separate overlay
    // views, torn down via dismissOverlays() instead.
    private var activeDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val currentUser = Session.currentUser()
        val otherUser = Session.otherUser()
        if (currentUser == null || otherUser == null) {
            // Session was cleared unexpectedly — bounce back to login
            // rather than crash on a null user.
            finish()
            return
        }

        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = MessageAdapter(currentUser)
        binding.messagesRecyclerView.adapter = adapter
        binding.messagesRecyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this).apply { stackFromEnd = true }
        adapter.onSwipeReply = { message -> enterReplyMode(message) }
        adapter.onMessageTap = { message, anchor -> showReactionBar(message, anchor) }
        adapter.onMessageLongPress = { message, anchor -> showActionMenu(message, anchor) }

        val displayName = if (otherUser == "katis1") "Kat" else "Kitty"
        binding.headerName.text = displayName

        repository = ChatRepository(currentUser, otherUser)
        // Lifecycle-aware: reconnect/resync happens automatically on
        // every onStart, teardown on every onStop, without manually
        // wiring visibilitychange-equivalent logic per screen.
        lifecycle.addObserver(repository)

        repository.onMessageAdded = { message ->
            runOnUiThread {
                if (messages.none { it.key == message.key }) {
                    messages.removeAll { it.key == message.key }
                messages.add(message)
                    messages.sortBy { it.time }
adapter.submitList(messages.toMutableList()) {
    binding.messagesRecyclerView.scrollToPosition(messages.size - 1)
            }
            repository.markSeen(message)
                    updateUnreadState()
                }
            }
        }

        repository.onMessageChanged = { message ->
            runOnUiThread {
                val index = messages.indexOfFirst { it.key == message.key }
                if (index >= 0) {
                    messages[index] = message
            adapter.submitList(messages.toMutableList()) {
                binding.messagesRecyclerView.scrollToPosition(messages.size - 1)
            }
                }
            }
        }

        repository.onConnectionStateChanged = { connected ->
            runOnUiThread {
                if (!connected) binding.headerStatus.text = "Connecting..."
            }
        }

        repository.onOtherUserPresence = { status ->
            runOnUiThread {
                binding.headerStatus.text = PresenceFormatter.format(status.last)
            }
        }

        repository.onOtherUserTyping = { isTyping ->
            runOnUiThread {
                binding.typingIndicator.visibility = if (isTyping) android.view.View.VISIBLE else android.view.View.GONE
                binding.typingIndicator.text = "$displayName is typing..."
            }
        }

        binding.sendButton.setOnClickListener {
            val text = binding.messageInput.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                val reply = replyingTo
                repository.sendMessage(
                    text,
                    replyTo = reply?.key,
                    replyText = reply?.let { MessageAdapter.previewText(it) },
                    replySender = reply?.name
                )
                binding.messageInput.setText("")
                exitReplyMode()
            }
        }

        binding.replyPreviewCancel.setOnClickListener { exitReplyMode() }

        binding.messageInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                repository.setTyping(true)
                typingRunnable?.let { typingHandler?.removeCallbacks(it) }
                typingHandler = typingHandler ?: Handler(Looper.getMainLooper())
                typingRunnable = Runnable { repository.setTyping(false) }
                typingHandler?.postDelayed(typingRunnable!!, 2000)
            }
        })
    }

    private fun enterReplyMode(message: Message) {
        replyingTo = message
        val displayName = if (Session.otherUser() == "katis1") "Kat" else "Kitty"
        binding.replyPreviewBarSender.text = if (message.name == Session.currentUser()) "You" else displayName
        binding.replyPreviewBarText.text = MessageAdapter.previewText(message)
        binding.replyPreviewBar.visibility = android.view.View.VISIBLE
    }

    private fun exitReplyMode() {
        replyingTo = null
        binding.replyPreviewBar.visibility = android.view.View.GONE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // Tears down whatever overlay (reaction bar or action menu) is
    // currently showing, plus its outside-tap scrim.
    private fun dismissOverlays() {
        binding.overlayContainer.removeAllViews()
        binding.overlayContainer.isClickable = false
    }

    private fun addScrim(onTap: () -> Unit) {
        val scrim = View(this)
        scrim.setOnClickListener { onTap() }
        scrim.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        binding.overlayContainer.addView(scrim)
        binding.overlayContainer.isClickable = true
    }

    // Places `view` (already added to overlayContainer) just above `anchor`,
    // flipping below it if there isn't room above, then fades + scales it in.
    private fun popInAboveAnchor(view: View, anchor: View) {
        view.alpha = 0f
        view.scaleX = 0.85f
        view.scaleY = 0.85f
        view.doOnPreDraw {
            val anchorLoc = IntArray(2)
            anchor.getLocationInWindow(anchorLoc)
            val containerLoc = IntArray(2)
            binding.overlayContainer.getLocationInWindow(containerLoc)

            val maxX = (binding.overlayContainer.width - view.width - dp(8)).coerceAtLeast(dp(8))
            val x = (anchorLoc[0] - containerLoc[0]).coerceIn(dp(8), maxX)
            var y = anchorLoc[1] - containerLoc[1] - view.height - dp(8)
            if (y < dp(8)) {
                y = anchorLoc[1] - containerLoc[1] + anchor.height + dp(8)
            }
            view.x = x.toFloat()
            view.y = y.toFloat()
            view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
        }
    }

    private fun toggleReaction(message: Message, emoji: String) {
        val mine = message.reactions?.get(Session.currentUser())
        repository.setReaction(message.key, if (mine == emoji) null else emoji)
    }

    // FEATURE 3 — Messenger-style tap-to-react floating emoji bar.
    private fun showReactionBar(message: Message, anchor: View) {
        dismissOverlays()
        addScrim { dismissOverlays() }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(com.privatechat.app.R.drawable.bg_reaction_bar)
            elevation = dp(4).toFloat()
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val emojis = listOf("❤️", "👍", "😂", "😮", "😢")
        for (emoji in emojis) {
            bar.addView(TextView(this).apply {
                text = emoji
                textSize = 22f
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener {
                    toggleReaction(message, emoji)
                    dismissOverlays()
                }
            })
        }
        bar.addView(TextView(this).apply {
            text = "➕"
            textSize = 20f
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener {
                dismissOverlays()
                showEmojiPicker(message)
            }
        })
        bar.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        binding.overlayContainer.addView(bar)
        popInAboveAnchor(bar, anchor)
    }

    // FEATURE 4 — Messenger-style 2s long-press action menu.
    private fun showActionMenu(message: Message, anchor: View) {
        dismissOverlays()
        addScrim { dismissOverlays() }

        val isMine = message.name == Session.currentUser()
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(com.privatechat.app.R.drawable.bg_popup_menu)
            elevation = dp(6).toFloat()
        }

        fun addItem(icon: String, label: String, action: () -> Unit) {
            val row = TextView(this).apply {
                text = "$icon   $label"
                textSize = 15f
                setTextColor(resources.getColor(com.privatechat.app.R.color.textPrimary, theme))
                setPadding(dp(20), dp(12), dp(24), dp(12))
                isClickable = true
                val ripple = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
                setBackgroundResource(ripple.resourceId)
                setOnClickListener {
                    dismissOverlays()
                    action()
                }
            }
            menu.addView(row)
        }

        addItem("😀", "React") { showReactionBar(message, anchor) }
        if (!message.deleted) addItem("↩", "Reply") { enterReplyMode(message) }
        if (isMine && !message.deleted) addItem("✏", "Edit") { showEditDialog(message) }
        if (isMine && !message.deleted) addItem("🗑", "Unsend") { repository.deleteMessage(message.key) }

        menu.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        binding.overlayContainer.addView(menu)
        popInAboveAnchor(menu, anchor)
    }

    private fun showEditDialog(message: Message) {
        val input = EditText(this).apply {
            setText(message.text)
            setSelection(text.length)
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        activeDialog = AlertDialog.Builder(this)
            .setTitle("Edit message")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newText = input.text.toString().trim()
                if (newText.isNotEmpty() && newText != message.text) {
                    repository.editMessage(message.key, newText)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // "+" in the reaction bar — a curated emoji grid stands in for the
    // system emoji picker/BottomSheet without pulling in a new dependency.
    private fun showEmojiPicker(message: Message) {
        val emojis = listOf(
            "😀", "😁", "😂", "🤣", "😊", "😍", "😘", "😜", "🤔", "😎",
            "😢", "😭", "😡", "😱", "🥳", "👍", "👎", "🙏", "👏", "🔥",
            "💯", "🎉", "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍"
        )
        val grid = GridLayout(this).apply {
            columnCount = 6
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        for (emoji in emojis) {
            grid.addView(TextView(this).apply {
                text = emoji
                textSize = 26f
                gravity = android.view.Gravity.CENTER
                layoutParams = GridLayout.LayoutParams().apply {
                    width = dp(48)
                    height = dp(48)
                }
                isClickable = true
                setOnClickListener {
                    toggleReaction(message, emoji)
                    activeDialog?.dismiss()
                }
            })
        }
        activeDialog = AlertDialog.Builder(this)
            .setTitle("Choose a reaction")
            .setView(ScrollView(this).apply { addView(grid) })
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateUnreadState() {
        // Hook point for a launcher/bottom-nav badge once that screen
        // exists — count is already computed reliably here via the
        // repository, independent of Activity lifecycle state.
        val unread = repository.unreadCount(messages)
        title = if (unread > 0) "Chat ($unread)" else "Chat"
    }

    override fun onDestroy() {
        super.onDestroy()
        activeDialog?.dismiss()
        repository.detachAll()
    }
}
