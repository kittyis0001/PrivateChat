package com.privatechat.app.ui.chat

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnPreDraw
import androidx.emoji2.emojipicker.EmojiPickerView
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.privatechat.app.data.Nicknames
import com.privatechat.app.data.Session
import com.privatechat.app.data.model.Message
import com.privatechat.app.data.repository.ChatRepository
import com.privatechat.app.databinding.ActivityChatBinding
import com.privatechat.app.media.CloudinaryUploader
import com.privatechat.app.voice.VoicePlaybackController
import com.privatechat.app.notification.NotificationRepository
import com.privatechat.app.ui.photo.PhotoViewerActivity
import com.privatechat.app.utils.NotificationAvatarFactory
import com.privatechat.app.utils.PresenceFormatter
import kotlinx.coroutines.launch

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var repository: ChatRepository
    private lateinit var notificationRepository: NotificationRepository
    private lateinit var adapter: MessageAdapter
    // Voice calls — independent of ChatRepository since a call needs
    // to ring whenever this device's process is alive, not tied to
    // whether the chat screen itself is currently open. See
    // CallSignalingRepository's own comment for why.
    private var callSignaling: com.privatechat.app.call.CallSignalingRepository? = null
    private var isLaunchingIncomingCall = false

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

    // Whether the OTHER user has blocked THIS one — mirrors
    // blocks/{otherUser}. This is the actual fix: previously nothing
    // read this, so a blocked user's own device never knew and could
    // still send messages freely.
    private var isBlockedByOther = false

    // Live custom nicknames (username -> nickname) from
    // ChatRepository.onNicknamesChanged — read fresh by every display
    // point below rather than captured once, so a nickname change is
    // reflected instantly everywhere without extra plumbing.
    private var nicknames: Map<String, String> = emptyMap()

    // Current vanish-mode duration in hours, or null if off — mirrors
    // the shared vanishMode/ Firebase node (write path is
    // repository.setVanishMode()).
    private var vanishModeDurationHours: Int? = null
    private var vanishCheckHandler: Handler? = null
    private var vanishCheckRunnable: Runnable? = null

    // Live profile photos (username -> Cloudinary URL) from
    // ChatRepository.onPhotosChanged — same live-map pattern as
    // nicknames above, so a Change DP on either device updates both
    // instantly with no refresh/re-login.
    private var photos: Map<String, String> = emptyMap()

    // Gallery permission (Change DP, step 1) — the actual image pick
    // below (photoPickerLauncher) doesn't need this on most modern
    // devices (Android's Photo Picker is permission-less on API 30+
    // with current Play services, and native on 33+), but the explicit
    // request is kept as requested and as the fallback path for
    // devices where that isn't available. Registered as property
    // initializers (same pattern LoginActivity's notification
    // permission launcher already uses) since registerForActivityResult
    // must happen before the Activity reaches STARTED.
    private val galleryPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchPhotoPicker()
        }
    private val photoPickerLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) uploadAndSavePhoto(uri)
        }

    // ── Chat image/video send (camera icon) ─────────────────────────
    // Same permission-then-picker shape as galleryPermissionLauncher/
    // photoPickerLauncher above, kept as its own pair of launchers
    // (rather than reusing the DP ones) so a media send never collides
    // with an in-flight Change-DP pick, and so image vs video request
    // the correct media-type permission on API 33+.
    private val mediaImagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchMediaImagePicker()
        }
    private val mediaVideoPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchMediaVideoPicker()
        }
    private val mediaImagePickerLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) openMediaPreview(uri, isVideo = false)
        }
    private val mediaVideoPickerLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) openMediaPreview(uri, isVideo = true)
        }
    private val mediaPreviewLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleMediaPreviewResult(result)
        }

    // Voice messages — mic permission requested the same way gallery
    // permission is above.
    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) beginVoiceRecording()
        }
    private var voiceRecorder: com.privatechat.app.voice.VoiceRecorder? = null
    private var recordingStartTimeMs = 0L
    private var recordingTimerHandler: Handler? = null
    private var recordingTimerRunnable: Runnable? = null
    // Live amplitude samples (0f..1f, normalized) captured while
    // recording via VoiceRecorder.currentAmplitude() — this device's
    // own real waveform data for the clip it's actively recording,
    // reused as-is for the preview row once recording stops (same
    // clip, same samples). Never sent anywhere — see WaveformUtils'
    // doc comment for why a received/replayed bubble instead shows a
    // stable pattern rather than this real data.
    private val recordingAmplitudes = mutableListOf<Float>()
    // The recorded-but-not-yet-sent file currently shown in
    // voicePreviewBar, if any. Its absolute path doubles as the
    // "source" VoicePlaybackController uses to identify this specific
    // local preview versus any bubble's remote URL.
    private var voicePreviewFile: java.io.File? = null
    private var voicePreviewDurationMs: Int = 0

    // The one dialog/bottom-sheet that can be open at a time (edit message
    // / emoji picker) — the reaction bar and action menu are separate
    // overlay views, torn down via dismissOverlays() instead.
    private var activeDialog: android.app.Dialog? = null

    companion object {
        // Marks the reaction bar / action menu view (as opposed to the
        // full-screen scrim) inside overlayContainer, so
        // dismissOverlaysAnimated() knows which child to animate out.
        private const val TAG_OVERLAY_CONTENT = "overlay_content"
        private val UNSEND_RED = android.graphics.Color.parseColor("#E53935")

        // How long after the last keystroke the typing indicator clears
        // on the other side, when the field isn't emptied first (see
        // the isBlank() early-clear above). Reduced from 2000ms — short
        // enough to feel instant, long enough not to flicker between
        // individual keystrokes during a normal typing pause.
        private const val TYPING_STOP_DELAY_MS = 1000L

        // How often the vanish-mode expiry sweep runs while the chat is
        // foregrounded. There's no server-side job for this (client-
        // only, per this feature's scope) — a message that ages out
        // while both users are away simply gets purged the next time
        // either one opens the app, which this interval keeps prompt
        // for whoever currently has it open.
        private const val VANISH_CHECK_INTERVAL_MS = 60_000L

        // How often the recording timer/waveform tick — a real
        // amplitude sample is captured on each tick, so this doubles
        // as the waveform's time resolution while recording.
        private const val RECORDING_TICK_MS = 200L

        // Read by ChatFirebaseMessagingService to suppress showing a
        // system notification while this chat is already on screen —
        // the incoming message is about to render directly via the
        // live Realtime Database listener instead. @Volatile since
        // it's written on the UI thread (onStart/onStop) and read from
        // a background/service thread.
        @Volatile
        var isForeground = false
    }

    override fun onStart() {
        super.onStart()
        isForeground = true
        // Catches up on anything that arrived while backgrounded (see
        // the isForeground guard in onMessageAdded below, in onCreate) —
        // "app ঢুকলে instant seen mark". Firebase's listener stays
        // attached the whole time for reliability, so `messages` is
        // already current; this just marks-seen what wasn't marked
        // while we were away.
        if (::repository.isInitialized) {
            val other = Session.otherUser()
            if (other != null) {
                messages.filter { it.name == other && !it.seen && !it.deleted && it.type == null }
                    .forEach { repository.markSeen(it) }
            }
            purgeExpiredMessages()
            restartVanishExpiryChecks()
        }
        callSignaling?.attachSessionListener()
    }

    override fun onStop() {
        super.onStop()
        isForeground = false
        stopVanishExpiryChecks()
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

        fun otherDisplayName() = Nicknames.resolve(otherUser, nicknames)
        fun myDisplayName() = Nicknames.resolve(currentUser, nicknames)

        binding.headerName.text = otherDisplayName()

        // "HEADER AVATAR — add the other user's avatar in the chat
        // header (left of the username)". Falls back to the same
        // generated colored-initial circle used elsewhere in the app
        // (NotificationAvatarFactory) when no custom photo is set yet,
        // so the header never shows a blank/broken image.
        fun refreshHeaderAvatar() {
            loadAvatarInto(binding.headerAvatar, otherUser, photos[otherUser])
        }
        refreshHeaderAvatar()
        binding.headerAvatar.setOnClickListener {
            val url = photos[otherUser]
            if (!url.isNullOrBlank()) {
                startActivity(PhotoViewerActivity.newIntent(this, url))
            }
        }

        repository = ChatRepository(currentUser, otherUser)
        notificationRepository = NotificationRepository(applicationContext)
        // Lifecycle-aware: reconnect/resync happens automatically on
        // every onStart, teardown on every onStop, without manually
        // wiring visibilitychange-equivalent logic per screen.
        lifecycle.addObserver(repository)

        binding.callButton.setOnClickListener {
            startActivity(
                android.content.Intent(this, com.privatechat.app.call.CallActivity::class.java).apply {
                    putExtra(com.privatechat.app.call.CallActivity.EXTRA_REMOTE_USER, otherUser)
                    putExtra(com.privatechat.app.call.CallActivity.EXTRA_IS_OUTGOING, true)
                    putExtra(com.privatechat.app.call.CallActivity.EXTRA_REMOTE_PHOTO_URL, photos[otherUser])
                }
            )
        }

        // Rings this device whenever a new call session appears
        // addressed to this user — see CallSignalingRepository's own
        // comment for why this is independent of ChatRepository's
        // Activity-lifecycle-bound listeners (still attached/detached
        // in onStart/onStop below, just as its own separate pair).
        callSignaling = com.privatechat.app.call.CallSignalingRepository(currentUser).apply {
            onSessionChanged = { session ->
                runOnUiThread {
                    if (session != null && session.status == "ringing" &&
                        session.callee == currentUser && !isLaunchingIncomingCall
                    ) {
                        isLaunchingIncomingCall = true
                        startActivity(
                            android.content.Intent(this@ChatActivity, com.privatechat.app.call.CallActivity::class.java).apply {
                                putExtra(com.privatechat.app.call.CallActivity.EXTRA_REMOTE_USER, session.caller)
                                putExtra(com.privatechat.app.call.CallActivity.EXTRA_IS_OUTGOING, false)
                                putExtra(com.privatechat.app.call.CallActivity.EXTRA_REMOTE_PHOTO_URL, photos[session.caller])
                            }
                        )
                    }
                    if (session == null || session.status != "ringing") {
                        isLaunchingIncomingCall = false
                    }
                }
            }
        }

        repository.onMessageAdded = { message ->
            runOnUiThread {
                if (messages.none { it.key == message.key }) {
                    messages.removeAll { it.key == message.key }
                messages.add(message)
                    messages.sortBy { it.time }
adapter.submitList(messages.toMutableList()) {
    binding.messagesRecyclerView.scrollToPosition(messages.size - 1)
            }
            // Only mark seen while the chat is actually visible —
            // WhatsApp-style "instant seen only while looking at the
            // screen". Firebase's listener + keepSynced keep delivering
            // onChildAdded events even while backgrounded (by design,
            // for reliability — see ChatRepository.onStop), which
            // previously meant a message got marked seen the instant it
            // arrived even if nobody was looking at the screen. If the
            // app is backgrounded when this fires, the message is still
            // added to the list (so it's there when the user returns)
            // but stays unseen until onStart's catch-up pass above
            // actually marks it.
            if (isForeground) {
                repository.markSeen(message)
            }
                    updateUnreadState()
                    purgeExpiredMessages()
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
                binding.typingIndicator.text = "${otherDisplayName()} is typing..."
            }
        }

        repository.onBlockStateChanged = { blocked, timestamp ->
            runOnUiThread {
                isBlockedByMe = blocked
                blockedAtMillis = timestamp
                updateBlockedUi()
            }
        }

        // The actual fix: know when the OTHER user has blocked me, so
        // sending can actually be prevented on the blocked user's own
        // device (guarded in the sendButton listener below) instead of
        // only ever hiding the compose bar on the blocker's screen.
        repository.onBlockedByOtherChanged = { blocked ->
            runOnUiThread {
                isBlockedByOther = blocked
            }
        }

        // "নিজের এবং অপরজনের যেন nickname চেঞ্জ করা যায়, instant update
        // হয় দুজনের বেলায়" — one shared Firebase node, so this fires
        // instantly on both devices the moment either one saves a change.
        repository.onNicknamesChanged = { map ->
            runOnUiThread {
                nicknames = map
                adapter.nicknames = map
                binding.headerName.text = otherDisplayName()
            }
        }

        // "Both users see the new DP instantly without refresh or
        // re-login" — one shared Firebase node (same pattern as
        // nicknames/), so this callback alone is the entire sync
        // mechanism for both the chat header and the 3-dot menu's own
        // avatar.
        repository.onPhotosChanged = { map ->
            runOnUiThread {
                photos = map
                refreshHeaderAvatar()
            }
        }

        // "যে কোনো একজন user সিলেক্ট করবে সেটা ২ জনের জন্য কাজ করবে" —
        // one shared node, fires instantly on both devices. Runs an
        // immediate sweep on every change (covers "turn on 2h when
        // there are already messages older than 2h" and "turn off"
        // cleanly stopping further purges) and restarts the periodic
        // check with the new duration.
        repository.onVanishModeChanged = { hours ->
            runOnUiThread {
                vanishModeDurationHours = hours
                purgeExpiredMessages()
                restartVanishExpiryChecks()
            }
        }

        binding.menuButton.setOnClickListener { showTopMenu(it) }

        binding.unblockButton.setOnClickListener {
            repository.setBlocked(false)
        }

        // Camera icon (see updateCameraButtonVisibility) — opens the
        // WhatsApp-style Photo/Video picker sheet.
        binding.cameraButton.setOnClickListener { showMediaPickerSheet() }

        binding.sendButton.setOnClickListener {
            val text = binding.messageInput.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                // Mic mode (see updateSendButtonIcon) — while actively
                // editing an existing message, this button always shows
                // Send instead, so empty+editing still means "nothing
                // to do", same as before this feature existed.
                if (editingMessage == null) requestVoiceRecording()
                return@setOnClickListener
            }
            if (isBlockedByOther) {
                // The other user has blocked this one — this is the
                // actual fix: previously nothing checked this, so a
                // blocked user could still send freely and it would
                // reach Firebase with no restriction.
                android.widget.Toast.makeText(
                    this,
                    "You can't send messages to this user",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
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
                    replySender = reply?.name,
                    onSent = {
                        // "Android writes a message to Firebase, Android
                        // immediately calls the Render API" — fires only
                        // after Firebase confirms the write, using the
                        // same preview text the recipient's own bubble
                        // would show (MessageAdapter.previewText handles
                        // voice/GIF special-casing identically).
                        notificationRepository.notifyNewMessage(
                            senderId = currentUser,
                            receiverId = otherUser,
                            senderName = myDisplayName(),
                            preview = MessageAdapter.previewText(
                                Message(name = currentUser, text = text)
                            )
                        )
                    }
                )
                binding.messageInput.setText("")
                exitReplyMode()
            }
        }

        // WhatsApp-style recording bar: cancel discards, the checkmark
        // stops and moves to the preview bar below.
        binding.voiceRecordingCancel.setOnClickListener { cancelVoiceRecording() }
        binding.voiceRecordingStop.setOnClickListener { finishVoiceRecording() }

        // Preview bar: delete discards and returns to the normal
        // compose bar (re-record), play/pause previews the exact file
        // that would be sent, send uploads it.
        binding.voicePreviewDelete.setOnClickListener { discardVoicePreview() }
        binding.voicePreviewPlayPause.setOnClickListener {
            voicePreviewFile?.let { VoicePlaybackController.togglePlayback(it.absolutePath) }
        }
        binding.voicePreviewSend.setOnClickListener { sendVoicePreview() }

        // Single owner of this callback slot — see MessageAdapter.
        // updateVoicePlaybackState's own comment for why bubbles don't
        // register their own listener here too. Reassigned fresh every
        // onCreate, which correctly replaces a previous (now-destroyed)
        // Activity instance's stale callback on recreate (e.g. the
        // dark-theme toggle), the same pattern every repository.onX
        // callback in this file already relies on.
        VoicePlaybackController.onStateChanged = { source, isPlaying, positionMs, durationMs ->
            runOnUiThread {
                adapter.updateVoicePlaybackState(source, isPlaying, positionMs, durationMs)
                val previewPath = voicePreviewFile?.absolutePath
                if (source != null && source == previewPath) {
                    updateVoicePreviewPill(isPlaying)
                    val effectiveDuration = if (durationMs > 0) durationMs else voicePreviewDurationMs
                    binding.voicePreviewWaveform.progress =
                        if (effectiveDuration > 0) positionMs.toFloat() / effectiveDuration else 0f
                    binding.voicePreviewTimer.text = formatVoiceTime(
                        if (isPlaying || positionMs > 0) positionMs else effectiveDuration
                    )
                }
            }
        }

        binding.replyPreviewCancel.setOnClickListener {
            if (editingMessage != null) exitEditMode() else exitReplyMode()
        }

        binding.messageInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                updateSendButtonIcon()
                updateCameraButtonVisibility()
                typingRunnable?.let { typingHandler?.removeCallbacks(it) }
                if (s.isNullOrBlank()) {
                    // Field is empty — backspaced clear, or just sent a
                    // message (setText("") after send fires this too) —
                    // the user is unambiguously done typing, so clear
                    // instantly instead of waiting out the debounce
                    // below. This is also what stops the old bug where
                    // sending a message could flash "typing..." at the
                    // other person right after send.
                    repository.setTyping(false)
                    return
                }
                repository.setTyping(true)
                typingHandler = typingHandler ?: Handler(Looper.getMainLooper())
                typingRunnable = Runnable { repository.setTyping(false) }
                typingHandler?.postDelayed(typingRunnable!!, TYPING_STOP_DELAY_MS)
            }
        })
    }

    private fun enterReplyMode(message: Message) {
        editingMessage = null
        binding.messageInput.setText("")
        replyingTo = message
        val displayName = Nicknames.resolve(Session.otherUser().orEmpty(), nicknames)
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

    // Shared by the chat header's other-user avatar and the 3-dot
    // menu's own-avatar section: a Cloudinary photo if one's been set,
    // otherwise the same generated colored-initial circle
    // NotificationAvatarFactory already draws for notifications — one
    // fallback look used everywhere an avatar can be missing.
    private fun loadAvatarInto(imageView: ImageView, username: String, photoUrl: String?) {
        if (!photoUrl.isNullOrBlank()) {
            Glide.with(this)
                .load(photoUrl)
                .transform(CircleCrop())
                .into(imageView)
        } else {
            val initial = Nicknames.resolve(username, nicknames).firstOrNull() ?: '?'
            val color = resources.getColor(com.privatechat.app.R.color.primary, theme)
            imageView.setImageBitmap(
                NotificationAvatarFactory.create(resources.displayMetrics.density, initial, color)
            )
        }
    }

    // "CHANGE DP FEATURE — When tapped: 1. Request Gallery permission."
    private fun requestChangeDp() {
        val permission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(this, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            launchPhotoPicker()
        } else {
            galleryPermissionLauncher.launch(permission)
        }
    }

    // Steps 2-3: open the gallery/photo picker. PickVisualMedia is the
    // modern picker — on API 30+ (via Play services) and 33+ natively
    // it needs no storage permission at all, but requestChangeDp()
    // above still requests one first as the explicit fallback path for
    // older devices, per the task's literal steps.
    private fun launchPhotoPicker() {
        photoPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    // Steps 4-6: upload to Cloudinary, save the URL to Firebase — step
    // 6 ("both users see it instantly") is then just
    // repository.onPhotosChanged firing on both devices, already wired
    // in onCreate.
    private fun uploadAndSavePhoto(uri: Uri) {
        val currentUser = Session.currentUser() ?: return
        android.widget.Toast.makeText(this, "Uploading photo…", android.widget.Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val url = CloudinaryUploader.uploadImage(applicationContext, uri)
                repository.setPhotoUrl(currentUser, url)
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    this@ChatActivity,
                    e.message ?: "Photo upload failed",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ── Chat image/video send (camera icon) ─────────────────────────

    // Mic when the input is empty, Send once there's text (see
    // updateSendButtonIcon's own comment) — the camera icon mirrors
    // that same empty/non-empty split, WhatsApp-style: visible while
    // composing is empty, hidden the instant typing starts, and hidden
    // while inline-editing an existing message (Send always shows then).
    private fun updateCameraButtonVisibility() {
        val hasText = binding.messageInput.text?.toString()?.trim().orEmpty().isNotEmpty()
        binding.cameraButton.visibility =
            if (hasText || editingMessage != null) android.view.View.GONE else android.view.View.VISIBLE
    }

    // Lightweight WhatsApp-style attach sheet — reuses the same simple
    // "TextView row in a BottomSheetDialog" shape as showEmojiPicker's
    // curated grid / showTopMenu's addItem rows elsewhere in this file,
    // rather than introducing a new XML bottom-sheet layout for two items.
    private fun showMediaPickerSheet() {
        val sheet = BottomSheetDialog(this)
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(com.privatechat.app.R.drawable.bg_popup_menu)
            elevation = dp(6).toFloat()
            setPadding(0, dp(8), 0, dp(8))
        }

        fun addRow(icon: String, label: String, action: () -> Unit) {
            val row = TextView(this).apply {
                text = "$icon   $label"
                textSize = 15f
                setTextColor(resources.getColor(com.privatechat.app.R.color.textPrimary, theme))
                setPadding(dp(20), dp(14), dp(20), dp(14))
                isClickable = true
                val ripple = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
                setBackgroundResource(ripple.resourceId)
                setOnClickListener {
                    sheet.dismiss()
                    action()
                }
            }
            menu.addView(row)
        }

        addRow("🖼️", "Photo") { requestMediaImagePick() }
        addRow("🎥", "Video") { requestMediaVideoPick() }

        sheet.setContentView(menu)
        activeDialog = sheet
        sheet.show()
    }

    private fun requestMediaImagePick() {
        val permission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(this, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) launchMediaImagePicker() else mediaImagePermissionLauncher.launch(permission)
    }

    private fun requestMediaVideoPick() {
        val permission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_VIDEO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(this, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) launchMediaVideoPicker() else mediaVideoPermissionLauncher.launch(permission)
    }

    private fun launchMediaImagePicker() {
        mediaImagePickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun launchMediaVideoPicker() {
        mediaVideoPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
        )
    }

    private fun openMediaPreview(uri: Uri, isVideo: Boolean) {
        mediaPreviewLauncher.launch(
            com.privatechat.app.ui.media.MediaPreviewActivity.newIntent(this, uri, isVideo)
        )
    }

    // Fires once MediaPreviewActivity finishes: RESULT_OK means the
    // upload already succeeded there (Cloudinary secure_url in hand),
    // so this only has to write the Firebase message — the exact same
    // messagesRef.push() path every other message type already uses,
    // via repository.sendMessage, so reactions/reply/edit/delete/vanish
    // mode all keep working on image/video messages for free.
    private fun handleMediaPreviewResult(result: androidx.activity.result.ActivityResult) {
        if (result.resultCode != android.app.Activity.RESULT_OK) return
        val data = result.data ?: return
        val url = data.getStringExtra(com.privatechat.app.ui.media.MediaPreviewActivity.RESULT_MEDIA_URL)
            ?: return
        val type = data.getStringExtra(com.privatechat.app.ui.media.MediaPreviewActivity.RESULT_MEDIA_TYPE)
        val caption = data.getStringExtra(com.privatechat.app.ui.media.MediaPreviewActivity.RESULT_CAPTION)
            ?.takeIf { it.isNotBlank() }

        if (isBlockedByOther) {
            android.widget.Toast.makeText(
                this, "You can't send messages to this user", android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }

        val prefix = if (type == com.privatechat.app.ui.media.MediaPreviewActivity.TYPE_VIDEO) "__video__" else "__image__"
        val text = "$prefix$url"
        val reply = replyingTo
        val sender = Session.currentUser() ?: return
        val receiver = Session.otherUser() ?: return
        repository.sendMessage(
            text,
            replyTo = reply?.key,
            replyText = reply?.let { MessageAdapter.previewText(it) },
            replySender = reply?.name,
            caption = caption,
            onSent = {
                notificationRepository.notifyNewMessage(
                    senderId = sender,
                    receiverId = receiver,
                    senderName = Nicknames.resolve(sender, nicknames),
                    preview = MessageAdapter.previewText(Message(name = sender, text = text, caption = caption))
                )
            }
        )
        exitReplyMode()
    }

    // ── Voice messages ───────────────────────────────────────────

    // Mic when the input is empty, Send once there's text — "কোনো word
    // type করলে send button, instant change হয়". While editing an
    // existing message, always Send (see the sendButton click
    // listener's early-return comment for why).
    private fun updateSendButtonIcon() {
        val hasText = binding.messageInput.text?.toString()?.trim().orEmpty().isNotEmpty()
        binding.sendButton.setImageResource(
            if (hasText || editingMessage != null) com.privatechat.app.R.drawable.ic_send
            else com.privatechat.app.R.drawable.ic_mic
        )
    }

    private fun formatVoiceTime(ms: Int): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    private fun requestVoiceRecording() {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            beginVoiceRecording()
        } else {
            micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun beginVoiceRecording() {
        val recorder = com.privatechat.app.voice.VoiceRecorder(applicationContext)
        if (!recorder.start()) {
            android.widget.Toast.makeText(this, "Couldn't start recording", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        voiceRecorder = recorder
        recordingAmplitudes.clear()
        binding.voiceRecordingWaveform.amplitudes = emptyList()
        binding.composeBar.visibility = android.view.View.GONE
        binding.voiceRecordingBar.visibility = android.view.View.VISIBLE
        recordingStartTimeMs = System.currentTimeMillis()
        startRecordingTimer()
    }

    private fun cancelVoiceRecording() {
        stopRecordingTimer()
        voiceRecorder?.cancel()
        voiceRecorder = null
        recordingAmplitudes.clear()
        binding.voiceRecordingBar.visibility = android.view.View.GONE
        binding.composeBar.visibility = android.view.View.VISIBLE
    }

    private fun finishVoiceRecording() {
        stopRecordingTimer()
        val recorder = voiceRecorder ?: return
        val stopped = recorder.stop()
        val file = recorder.outputFile
        voiceRecorder = null
        binding.voiceRecordingBar.visibility = android.view.View.GONE
        if (!stopped || file == null || !file.exists() || file.length() == 0L) {
            android.widget.Toast.makeText(this, "Recording too short", android.widget.Toast.LENGTH_SHORT).show()
            recordingAmplitudes.clear()
            binding.composeBar.visibility = android.view.View.VISIBLE
            return
        }
        showVoicePreview(file)
    }

    // Ticks the recording timer AND samples live amplitude on the same
    // 200ms beat — one Handler loop instead of two, since both need to
    // run at roughly the same cadence for the rest of this recording's
    // life anyway. "Show a live waveform while recording": each tick
    // appends one real sample from VoiceRecorder.currentAmplitude(),
    // normalized against MediaRecorder's raw 0..32767 scale.
    private fun startRecordingTimer() {
        stopRecordingTimer()
        val handler = Handler(Looper.getMainLooper())
        recordingTimerHandler = handler
        val runnable = object : Runnable {
            override fun run() {
                val elapsedMs = (System.currentTimeMillis() - recordingStartTimeMs).toInt()
                binding.voiceRecordingTimer.text = formatVoiceTime(elapsedMs)

                val amplitude = voiceRecorder?.currentAmplitude()
                if (amplitude != null) {
                    recordingAmplitudes.add((amplitude / 32767f).coerceIn(0f, 1f))
                    binding.voiceRecordingWaveform.amplitudes = recordingAmplitudes.toList()
                }

                handler.postDelayed(this, RECORDING_TICK_MS)
            }
        }
        recordingTimerRunnable = runnable
        handler.post(runnable)
    }

    private fun stopRecordingTimer() {
        recordingTimerRunnable?.let { recordingTimerHandler?.removeCallbacks(it) }
        recordingTimerRunnable = null
    }

    // "Voice message send করার আগে pause, resume থাকবে এবং শোনা যাবে,
    // delete থাকবে আবার record করার জন্য" — this is that preview state.
    // Reuses recordingAmplitudes as-is: same clip, same real samples
    // already captured while it was being recorded.
    private fun showVoicePreview(file: java.io.File) {
        voicePreviewFile = file
        binding.voicePreviewBar.visibility = android.view.View.VISIBLE
        updateVoicePreviewPill(isPlaying = false)
        binding.voicePreviewWaveform.amplitudes = recordingAmplitudes.toList()
        binding.voicePreviewWaveform.progress = 0f

        try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever
                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toIntOrNull() ?: 0
            retriever.release()
            voicePreviewDurationMs = durationMs
            binding.voicePreviewTimer.text = formatVoiceTime(durationMs)
        } catch (e: Exception) {
            voicePreviewDurationMs = 0
            binding.voicePreviewTimer.text = "0:00"
        }
    }

    // Pause/Resume pill: icon + text + accent color swap, matching the
    // reference screenshots — gray "Pause" while playing, primary-
    // colored "Resume" while paused.
    private fun updateVoicePreviewPill(isPlaying: Boolean) {
        binding.voicePreviewPlayPauseIcon.setImageResource(
            if (isPlaying) com.privatechat.app.R.drawable.ic_pause
            else com.privatechat.app.R.drawable.ic_mic
        )
        binding.voicePreviewPlayPauseLabel.text = if (isPlaying) "Pause" else "Resume"
        val pillColor = if (isPlaying) {
            resources.getColor(com.privatechat.app.R.color.textSecondary, theme)
        } else {
            resources.getColor(com.privatechat.app.R.color.primary, theme)
        }
        binding.voicePreviewPlayPause.background.setTint(pillColor)
    }

    private fun discardVoicePreview() {
        val file = voicePreviewFile
        if (file != null) {
            VoicePlaybackController.stopIfSource(file.absolutePath)
            file.delete()
        }
        voicePreviewFile = null
        recordingAmplitudes.clear()
        binding.voicePreviewBar.visibility = android.view.View.GONE
        binding.composeBar.visibility = android.view.View.VISIBLE
    }

    // "Cloudinary use করবে voice message এর জন্য" — CloudinaryUploader.
    // uploadAudio() (video/upload endpoint — Cloudinary's own
    // convention for audio, see that function's comment), then the
    // exact same __voice__ prefix Message.kt/MessageAdapter already
    // recognized before this feature existed.
    private fun sendVoicePreview() {
        val file = voicePreviewFile ?: return
        if (isBlockedByOther) {
            android.widget.Toast.makeText(
                this, "You can't send messages to this user", android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        val currentUser = Session.currentUser() ?: return
        val otherUser = Session.otherUser() ?: return
        VoicePlaybackController.stopIfSource(file.absolutePath)
        voicePreviewFile = null
        recordingAmplitudes.clear()
        binding.voicePreviewBar.visibility = android.view.View.GONE
        binding.composeBar.visibility = android.view.View.VISIBLE

        lifecycleScope.launch {
            try {
                val url = CloudinaryUploader.uploadAudio(file)
                repository.sendMessage(
                    "__voice__$url",
                    onSent = {
                        notificationRepository.notifyNewMessage(
                            senderId = currentUser,
                            receiverId = otherUser,
                            senderName = Nicknames.resolve(currentUser, nicknames),
                            preview = "🎤 Voice message"
                        )
                    }
                )
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    this@ChatActivity,
                    e.message ?: "Voice message failed to send",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } finally {
                file.delete()
            }
        }
    }

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

        // "ONLY ADD THIS SECTION" — top profile row: own avatar with a
        // small "+" badge (opens Change DP), own nickname, and an
        // "Upload Story" subtitle line matching the reference image.
        // Nothing below this point in the menu (existing items,
        // spacing, colors, radius, animation) was touched.
        val currentUserForProfile = Session.currentUser()
        if (currentUserForProfile != null) {
            val avatarSize = dp(52)
            val avatarFrame = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(avatarSize, avatarSize)
            }
            val avatarImage = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(avatarSize, avatarSize)
                isClickable = true
                setOnClickListener {
                    val url = photos[currentUserForProfile]
                    if (!url.isNullOrBlank()) {
                        dismissOverlays()
                        startActivity(PhotoViewerActivity.newIntent(this@ChatActivity, url))
                    }
                }
            }
            loadAvatarInto(avatarImage, currentUserForProfile, photos[currentUserForProfile])
            avatarFrame.addView(avatarImage)

            val plusBadgeSize = dp(20)
            val plusBadge = TextView(this).apply {
                text = "+"
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                setTextColor(resources.getColor(com.privatechat.app.R.color.white, theme))
                setBackgroundResource(com.privatechat.app.R.drawable.bg_dp_plus_badge)
                layoutParams = FrameLayout.LayoutParams(plusBadgeSize, plusBadgeSize).apply {
                    gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                }
                isClickable = true
                setOnClickListener {
                    dismissOverlays()
                    requestChangeDp()
                }
            }
            avatarFrame.addView(plusBadge)

            val nameColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                // No weight here — this is a WRAP_CONTENT menu, and
                // layout_weight on a child of a WRAP_CONTENT parent
                // makes Android measure that child against near-max
                // available width instead of its actual content, which
                // is exactly what blew the whole menu out wide. Plain
                // WRAP_CONTENT sizes it to just the name/subtitle text,
                // same width the menu had before this section existed.
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp(14)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                addView(TextView(this@ChatActivity).apply {
                    text = Nicknames.resolve(currentUserForProfile, nicknames)
                    textSize = 16f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(resources.getColor(com.privatechat.app.R.color.textPrimary, theme))
                })
                addView(TextView(this@ChatActivity).apply {
                    text = "Upload Story"
                    textSize = 12f
                    setTextColor(resources.getColor(com.privatechat.app.R.color.textSecondary, theme))
                    setPadding(0, dp(2), 0, 0)
                })
            }

            menu.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(14))
                addView(avatarFrame)
                addView(nameColumn)
            })
            val divider = View(this).apply {
                // Starts at 0dp width on purpose: unlike MATCH_PARENT
                // (which caused the exact width bug just fixed for
                // nameColumn — a child asking for more space than its
                // WRAP_CONTENT parent has forces that parent to expand
                // toward the screen width during measurement), a 0dp
                // child contributes nothing to menu's own width
                // calculation. Once menu has settled to its real,
                // content-driven width (from the existing text rows),
                // doOnPreDraw below stretches this one view to match it
                // — a full-width-looking line without ever influencing
                // how wide the menu itself measures as.
                layoutParams = LinearLayout.LayoutParams(0, dp(1))
                setBackgroundColor(resources.getColor(com.privatechat.app.R.color.popupBorder, theme))
            }
            menu.addView(divider)
            menu.doOnPreDraw {
                divider.layoutParams = divider.layoutParams.apply { width = menu.width }
            }
        }

        val muted = Session.isMuted()
        addItem("\u270F\uFE0F", "Change Nickname") {
            showChangeNicknameDialog()
        }
        addItem("\uD83D\uDDBC\uFE0F", "Change DP") {
            requestChangeDp()
        }
        addItem(if (muted) "\uD83D\uDD14" else "\uD83D\uDD15", if (muted) "Unmute Notifications" else "Mute Notifications") {
            toggleMute()
        }
        addItem("\uD83C\uDF19", "Dark Theme", checked = Session.isDarkThemeEnabled()) {
            toggleDarkTheme()
        }
        addItem("\uD83D\uDDD1\uFE0F", "Delete All Chat", textColor = UNSEND_RED) {
            confirmDeleteAllChat()
        }
        val vanishLabel = vanishModeDurationHours?.let { "Vanish Mode (${it}h)" } ?: "Custom Vanish Mode"
        addItem("\uD83D\uDC7B", vanishLabel, checked = vanishModeDurationHours != null) {
            showVanishModeDialog()
        }
        addItem(if (isBlockedByMe) "\u2705" else "\uD83D\uDEAB", if (isBlockedByMe) "Unblock User" else "Block User") {
            repository.setBlocked(!isBlockedByMe)
        }
        addItem("\uD83D\uDEAA", "Log Out") {
            confirmLogout()
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

    // "নিজের এবং অপরজনের যেন nickname চেঞ্জ করা যায়" — a single dialog
    // with both fields at once, since this is always exactly a
    // two-person conversation. Pre-filled with whatever's currently
    // resolved (custom nickname if set, otherwise the Kat/Kitty
    // default) so opening it never shows a blank/misleading value.
    // Redesigned to match the app's own rounded-card, purple-accent
    // aesthetic (colors.xml / bg_popup_menu, already used by the other
    // popups) instead of a plain system AlertDialog: a circular icon
    // badge, real title/subtitle typography, and pill-shaped inputs
    // (reusing @drawable/bg_input_field, the same shape as the compose
    // bar's own message field) instead of bare EditTexts.
    private fun showChangeNicknameDialog() {
        val currentUser = Session.currentUser() ?: return
        val otherUser = Session.otherUser() ?: return

        val primaryColor = resources.getColor(com.privatechat.app.R.color.primary, theme)
        val textPrimaryColor = resources.getColor(com.privatechat.app.R.color.textPrimary, theme)
        val textSecondaryColor = resources.getColor(com.privatechat.app.R.color.textSecondary, theme)

        val iconBadge = TextView(this).apply {
            text = "\u270F\uFE0F"
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(primaryColor)
            }
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(6)
            }
        }

        val title = TextView(this).apply {
            text = "Change Nickname"
            textSize = 18f
            setTextColor(textPrimaryColor)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "Personalize how each of you appears in this chat"
            textSize = 12f
            setTextColor(textSecondaryColor)
            gravity = android.view.Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(18))
        }

        fun fieldLabel(text: String, topMargin: Int = dp(10)) = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(textSecondaryColor)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(4), 0, dp(4), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { this.topMargin = topMargin }
        }
        fun styledInput(prefill: String) = android.widget.EditText(this).apply {
            setText(prefill)
            setSelection(prefill.length)
            setSingleLine(true)
            textSize = 15f
            setTextColor(textPrimaryColor)
            setBackgroundResource(com.privatechat.app.R.drawable.bg_input_field)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val myInput = styledInput(Nicknames.resolve(currentUser, nicknames))
        val otherInput = styledInput(Nicknames.resolve(otherUser, nicknames))

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(6))
            addView(iconBadge)
            addView(title)
            addView(subtitle)
            addView(fieldLabel("Your nickname", topMargin = 0))
            addView(myInput)
            addView(fieldLabel("${Nicknames.defaultFor(otherUser)}'s nickname", topMargin = dp(14)))
            addView(otherInput)
        }

        activeDialog = android.app.AlertDialog.Builder(this)
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                repository.setNickname(currentUser, myInput.text.toString())
                repository.setNickname(otherUser, otherInput.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()

        // Rounded, theme-aware window background (the same drawable the
        // other popups already use) instead of the OS's plain white
        // rectangle — this is what actually makes it read as part of
        // this app rather than a generic system dialog.
        activeDialog?.window?.setBackgroundDrawableResource(com.privatechat.app.R.drawable.bg_popup_menu)
    }

    // "same to same nick change এর মতো premium bubble" — same visual
    // language as showChangeNicknameDialog() (circular icon badge,
    // title/subtitle, rounded theme-aware window), but a vertical list
    // of 4 selectable rows instead of input fields: each tap commits
    // and closes immediately, no separate Save button, with the
    // currently-active option checked.
    private fun showVanishModeDialog() {
        val primaryColor = resources.getColor(com.privatechat.app.R.color.primary, theme)
        val textPrimaryColor = resources.getColor(com.privatechat.app.R.color.textPrimary, theme)
        val textSecondaryColor = resources.getColor(com.privatechat.app.R.color.textSecondary, theme)
        val currentSelection = vanishModeDurationHours

        val iconBadge = TextView(this).apply {
            text = "\uD83D\uDC7B"
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(primaryColor)
            }
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(6)
            }
        }

        val title = TextView(this).apply {
            text = "Vanish Mode"
            textSize = 18f
            setTextColor(textPrimaryColor)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "New messages disappear for both of you after the selected time"
            textSize = 12f
            setTextColor(textSecondaryColor)
            gravity = android.view.Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(16))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(10))
            addView(iconBadge)
            addView(title)
            addView(subtitle)
        }

        lateinit var dialogRef: android.app.AlertDialog

        fun optionRow(label: String, duration: Int?): View {
            val isActive = duration == currentSelection
            return TextView(this).apply {
                text = if (isActive) "$label   \u2713" else label
                textSize = 15f
                setTextColor(if (isActive) primaryColor else textPrimaryColor)
                setTypeface(typeface, if (isActive) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setPadding(dp(4), dp(14), dp(4), dp(14))
                isClickable = true
                val ripple = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
                setBackgroundResource(ripple.resourceId)
                setOnClickListener {
                    val myName = Nicknames.resolve(Session.currentUser().orEmpty(), nicknames)
                    repository.setVanishMode(duration, myName)
                    dialogRef.dismiss()
                }
            }
        }

        listOf("2 hours" to 2, "8 hours" to 8, "24 hours" to 24, "Off" to null).forEach { (label, duration) ->
            container.addView(optionRow(label, duration))
        }

        dialogRef = android.app.AlertDialog.Builder(this)
            .setView(container)
            .create()
        activeDialog = dialogRef
        dialogRef.show()
        dialogRef.window?.setBackgroundDrawableResource(com.privatechat.app.R.drawable.bg_popup_menu)
    }

    // Deletes any loaded message older than the active vanish-mode
    // duration, for both users — "২ ঘন্টা পর message seen হোক বা না
    // হোক vanish হয়ে যাবে ২ জনের পক্ষ থেকে". Uses each message's own
    // `time` (when it was sent), not seen state. System messages
    // (type == "system", the on/off announcements themselves) are
    // excluded so the vanish-mode history stays visible.
    private fun purgeExpiredMessages() {
        val hours = vanishModeDurationHours ?: return
        val cutoffAgeMillis = hours * 60L * 60L * 1000L
        val now = System.currentTimeMillis()
        messages.filter { it.type == null && (now - it.time) >= cutoffAgeMillis }
            .forEach { repository.deleteExpiredMessage(it.key) }
    }

    // No server-side job exists for this (client-only, per this
    // feature's scope) — this periodic sweep is what keeps expiry
    // prompt while at least one device has the chat open, on top of
    // the immediate checks in onStart/onMessageAdded/onVanishModeChanged
    // that catch anything that aged out while nobody was looking.
    private fun restartVanishExpiryChecks() {
        stopVanishExpiryChecks()
        if (vanishModeDurationHours == null) return
        val handler = Handler(Looper.getMainLooper())
        vanishCheckHandler = handler
        val runnable = object : Runnable {
            override fun run() {
                purgeExpiredMessages()
                handler.postDelayed(this, VANISH_CHECK_INTERVAL_MS)
            }
        }
        vanishCheckRunnable = runnable
        handler.postDelayed(runnable, VANISH_CHECK_INTERVAL_MS)
    }

    private fun stopVanishExpiryChecks() {
        vanishCheckRunnable?.let { vanishCheckHandler?.removeCallbacks(it) }
        vanishCheckHandler = null
        vanishCheckRunnable = null
    }

    private fun confirmLogout() {
        activeDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Log out?")
            .setMessage("You'll need to log in again to open this chat.")
            .setPositiveButton("Log Out") { _, _ -> performLogout() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performLogout() {
        Session.clear()
        val intent = android.content.Intent(this, com.privatechat.app.ui.login.LoginActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
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
        stopRecordingTimer()
        voiceRecorder?.cancel()
        VoicePlaybackController.stop()
        repository.detachAll()
        callSignaling?.detachAll(null)
    }
}
