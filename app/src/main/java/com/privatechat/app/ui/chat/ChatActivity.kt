package com.privatechat.app.ui.chat

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnPreDraw
import androidx.emoji2.emojipicker.EmojiPickerView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
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

    // The message currently being edited inline via the compose bar, if
    // any. Mutually exclusive with replyingTo — entering one clears the
    // other. Non-null means Send commits an edit instead of a new message.
    private var editingMessage: Message? = null

    // Whether *this* user has blocked the other one, and when — mirrors
    // blocks/{currentUser} in Firebase, read-only here (write path is
    // repository.setBlocked()).
    private var isBlockedByMe = false
    private var blockedAtMillis = 0L

    // The one dialog/bottom-sheet that can be open at a time (edit message
    // / emoji picker) — the reaction bar and action menu are separate
    // overlay views, torn down via dismissOverlays() instead.
    private var activeDialog: android.app.Dialog? = null

    private companion object {
        // Marks the reaction bar / action menu view (as opposed to the
        // full-screen scrim) inside overlayContainer, so
        // dismissOverlaysAnimated() knows which child to animate out.
        const val TAG_OVERLAY_CONTENT = "overlay_content"
        val UNSEND_RED = android.graphics.Color.parseColor("#E53935")
    }

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

        // Firebase's onChildRemoved fires on BOTH devices the instant a
        // message node is removed (e.g. deleteAllChat()) — this is what
        // actually makes "Delete All Chat" vanish messages on both sides;
        // without it the local list never learned a node was gone.
        repository.onMessageRemoved = { key ->
            runOnUiThread {
                if (messages.removeAll { it.key == key }) {
                    // ListAdapter's default ItemAnimator fades/slides removed
                    // rows out on its own — this alone is the "হালকা animation".
                    adapter.submitList(messages.toMutableList())
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
                binding.headerStatus.text = PresenceFormatter.format(status)
            }
        }

        repository.onOtherUserTyping = { isTyping ->
            runOnUiThread {
                binding.typingIndicator.visibility = if (isTyping) android.view.View.VISIBLE else android.view.View.GONE
                binding.typingIndicator.text = "$displayName is typing..."
            }
        }

        repository.onBlockStateChanged = { blocked, timestamp ->
            runOnUiThread {
                isBlockedByMe = blocked
                blockedAtMillis = timestamp
                updateBlockedUi()
            }
        }

        binding.menuButton.setOnClickListener { showTopMenu(it) }

        binding.unblockButton.setOnClickListener {
            repository.setBlocked(false)
        }

        binding.sendButton.setOnClickListener {
            val text = binding.messageInput.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@setOnClickListener
            val editing = editingMessage
            if (editing != null) {
                // WhatsApp-style inline edit: Send commits the edit in
                // place instead of posting a new message.
                if (text != editing.text) {
                    repository.editMessage(editing.key, text)
                }
                exitEditMode()
            } else {
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

        binding.replyPreviewCancel.setOnClickListener {
            if (editingMessage != null) exitEditMode() else exitReplyMode()
        }

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
        editingMessage = null
        binding.messageInput.setText("")
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

    // WhatsApp-style inline edit: reuses the reply preview bar (as an
    // "Editing message" indicator) and loads the original text straight
    // into the compose input, so Send commits the edit in place rather
    // than opening a separate dialog.
    private fun enterEditMode(message: Message) {
        replyingTo = null
        editingMessage = message
        binding.replyPreviewBarSender.text = "Editing message"
        binding.replyPreviewBarText.text = ""
        binding.replyPreviewBar.visibility = android.view.View.VISIBLE
        binding.messageInput.setText(message.text)
        binding.messageInput.setSelection(binding.messageInput.text?.length ?: 0)
        binding.messageInput.requestFocus()
    }

    private fun exitEditMode() {
        editingMessage = null
        binding.replyPreviewBar.visibility = android.view.View.GONE
        binding.messageInput.setText("")
    }

    // Swaps the compose bar for a "You blocked this contact" banner
    // (WhatsApp-style) while this user has the other one blocked.
    private fun updateBlockedUi() {
        if (isBlockedByMe) {
            binding.composeBar.visibility = android.view.View.GONE
            binding.blockedBanner.visibility = android.view.View.VISIBLE
            val timeStr = if (blockedAtMillis > 0) {
                java.text.SimpleDateFormat("d MMM, h:mm a", java.util.Locale.getDefault())
                    .format(java.util.Date(blockedAtMillis))
            } else null
            binding.blockedBannerText.text = if (timeStr != null) {
                "You blocked this contact \u2014 $timeStr"
            } else {
                "You blocked this contact"
            }
        } else {
            binding.blockedBanner.visibility = android.view.View.GONE
            binding.composeBar.visibility = android.view.View.VISIBLE
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // Tears down whatever overlay (reaction bar or action menu) is
    // currently showing, plus its outside-tap scrim.
    private fun dismissOverlays() {
        binding.overlayContainer.removeAllViews()
        binding.overlayContainer.isClickable = false
    }

    // Same teardown, but fades + shrinks the popup/menu content first —
    // used for the "tap outside to close" gesture so closing feels like
    // the reverse of the WhatsApp/Messenger open animation instead of an
    // abrupt cut.
    private fun dismissOverlaysAnimated() {
        val content = (0 until binding.overlayContainer.childCount)
            .map { binding.overlayContainer.getChildAt(it) }
            .firstOrNull { it.tag == TAG_OVERLAY_CONTENT }
        if (content == null) {
            dismissOverlays()
            return
        }
        binding.overlayContainer.isClickable = false
        content.animate()
            .alpha(0f)
            .scaleX(0.85f)
            .scaleY(0.85f)
            .setDuration(120)
            .withEndAction { dismissOverlays() }
            .start()
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

    // Places `view` right-aligned just below `anchor` and slides it down
    // (translationY + fade) instead of scaling — the "drops down from the
    // 3-dot button" WhatsApp-style open animation.
    private fun popInBelowAnchorRightAligned(view: View, anchor: View) {
        view.alpha = 0f
        view.translationY = -dp(16).toFloat()
        view.doOnPreDraw {
            val anchorLoc = IntArray(2)
            anchor.getLocationInWindow(anchorLoc)
            val containerLoc = IntArray(2)
            binding.overlayContainer.getLocationInWindow(containerLoc)

            val rightEdge = anchorLoc[0] - containerLoc[0] + anchor.width
            val maxX = (binding.overlayContainer.width - view.width - dp(8)).coerceAtLeast(dp(8))
            val x = (rightEdge - view.width).coerceIn(dp(8), maxX)
            val y = anchorLoc[1] - containerLoc[1] + anchor.height + dp(4)
            view.x = x.toFloat()
            view.y = y.toFloat()
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(200)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    // FEATURE — WhatsApp-style top-right 3-dot overflow menu: Mute
    // Notifications, Dark Theme, Delete All Chat, Block/Unblock User.
    private fun showTopMenu(anchor: View) {
        dismissOverlays()
        addScrim { dismissOverlaysAnimated() }

        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(com.privatechat.app.R.drawable.bg_popup_menu)
            elevation = dp(6).toFloat()
            minimumWidth = dp(220)
            tag = TAG_OVERLAY_CONTENT
        }

        fun addItem(icon: String, label: String, checked: Boolean = false, textColor: Int? = null, action: () -> Unit) {
            val row = TextView(this).apply {
                text = if (checked) "$icon   $label   \u2713" else "$icon   $label"
                textSize = 15f
                setTextColor(textColor ?: resources.getColor(com.privatechat.app.R.color.textPrimary, theme))
                setPadding(dp(18), dp(13), dp(18), dp(13))
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

        val muted = Session.isMuted()
        addItem(if (muted) "\uD83D\uDD14" else "\uD83D\uDD15", if (muted) "Unmute Notifications" else "Mute Notifications") {
            toggleMute()
        }
        addItem("\uD83C\uDF19", "Dark Theme", checked = Session.isDarkThemeEnabled()) {
            toggleDarkTheme()
        }
        addItem("\uD83D\uDDD1\uFE0F", "Delete All Chat", textColor = UNSEND_RED) {
            confirmDeleteAllChat()
        }
        addItem(if (isBlockedByMe) "\u2705" else "\uD83D\uDEAB", if (isBlockedByMe) "Unblock User" else "Block User") {
            repository.setBlocked(!isBlockedByMe)
        }

        menu.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        binding.overlayContainer.addView(menu)
        popInBelowAnchorRightAligned(menu, anchor)
    }

    private fun toggleMute() {
        val nowMuted = !Session.isMuted()
        Session.setMuted(nowMuted)
        android.widget.Toast.makeText(
            this,
            if (nowMuted) "Notifications muted" else "Notifications unmuted",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    // AppCompatDelegate.setDefaultNightMode() automatically recreates the
    // current AppCompatActivity to apply the new theme immediately — the
    // instant WhatsApp-style switch, no manual recreate() needed.
    private fun toggleDarkTheme() {
        val enabled = !Session.isDarkThemeEnabled()
        Session.setDarkThemeEnabled(enabled)
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            if (enabled) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    private fun confirmDeleteAllChat() {
        activeDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Delete all chats?")
            .setMessage("This will permanently delete all messages for both of you. This can't be undone.")
            .setPositiveButton("Delete") { _, _ -> repository.deleteAllChat() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleReaction(message: Message, emoji: String) {
        val mine = message.reactions?.get(Session.currentUser())
        repository.setReaction(message.key, if (mine == emoji) null else emoji)
    }

    // FEATURE 3 — Messenger-style tap-to-react floating emoji bar.
    private fun showReactionBar(message: Message, anchor: View) {
        dismissOverlays()
        addScrim { dismissOverlaysAnimated() }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(com.privatechat.app.R.drawable.bg_reaction_bar)
            elevation = dp(4).toFloat()
            setPadding(dp(6), dp(6), dp(6), dp(6))
            tag = TAG_OVERLAY_CONTENT
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
        addScrim { dismissOverlaysAnimated() }

        val isMine = message.name == Session.currentUser()
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(com.privatechat.app.R.drawable.bg_popup_menu)
            elevation = dp(6).toFloat()
            minimumWidth = dp(230)
            tag = TAG_OVERLAY_CONTENT
        }

        fun addItem(icon: String, label: String, textColor: Int? = null, action: () -> Unit) {
            val row = TextView(this).apply {
                text = "$icon   $label"
                textSize = 16f
                setTextColor(textColor ?: resources.getColor(com.privatechat.app.R.color.textPrimary, theme))
                setPadding(dp(18), dp(14), dp(18), dp(14))
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

        addItem("😊", "React") { showReactionBar(message, anchor) }
        if (!message.deleted) addItem("↩️", "Reply") { enterReplyMode(message) }
        if (isMine && !message.deleted) addItem("✏️", "Edit") { enterEditMode(message) }
        if (!message.deleted) addItem("📋", "Copy") { copyMessageToClipboard(message) }
        if (isMine && !message.deleted) {
            addItem("🗑️", "Unsend", textColor = UNSEND_RED) { repository.deleteMessage(message.key) }
        }

        menu.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        binding.overlayContainer.addView(menu)
        popInAboveAnchor(menu, anchor)
    }

    // FEATURE — Copy (message text, or the underlying URL for a voice/GIF
    // message), matching the requested WhatsApp/Messenger "Copy" action.
    private fun copyMessageToClipboard(message: Message) {
        val content = when {
            message.isVoice() -> message.voiceUrl()
            message.isGif() -> message.gifUrl()
            else -> message.text
        }
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Message", content))
        android.widget.Toast.makeText(this, "Message copied", android.widget.Toast.LENGTH_SHORT).show()
    }

    // "+" in the reaction bar. Prefers Android's Jetpack system-style emoji
    // picker (EmojiPickerView, the same widget/behavior used system-wide),
    // hosted in a BottomSheet; if that widget can't be constructed on this
    // device/build, falls back to a curated emoji grid in its own
    // BottomSheet so the user still gets a picker either way.
    private fun showEmojiPicker(message: Message) {
        val sheet = BottomSheetDialog(this)
        val systemPicker = createSystemEmojiPicker(message, sheet)
        sheet.setContentView(systemPicker ?: buildCuratedEmojiGrid(message, sheet))
        activeDialog = sheet
        sheet.show()
    }

    private fun createSystemEmojiPicker(message: Message, sheet: BottomSheetDialog): View? {
        return try {
            EmojiPickerView(this).apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(360))
                setOnEmojiPickedListener { item ->
                    toggleReaction(message, item.emoji)
                    sheet.dismiss()
                }
            }
        } catch (e: Throwable) {
            // androidx.emoji2's EmojiPickerView failed to construct/render
            // on this device/build — fall back to the curated grid instead
            // of crashing the reaction flow.
            null
        }
    }

    private fun buildCuratedEmojiGrid(message: Message, sheet: BottomSheetDialog): View {
        val emojis = listOf(
            "😀", "😁", "😂", "🤣", "😊", "😍", "😘", "😜", "🤔", "😎",
            "😢", "😭", "😡", "😱", "🥳", "👍", "👎", "🙏", "👏", "🔥",
            "💯", "🎉", "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍"
        )
        val grid = GridLayout(this).apply {
            columnCount = 6
            setPadding(dp(16), dp(16), dp(16), dp(16))
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
                    sheet.dismiss()
                }
            })
        }
        return ScrollView(this).apply { addView(grid) }
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
