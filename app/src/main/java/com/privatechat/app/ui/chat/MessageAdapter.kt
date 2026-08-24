package com.privatechat.app.ui.chat

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import androidx.core.view.doOnPreDraw
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import android.text.method.LinkMovementMethod
import com.privatechat.app.data.Nicknames
import com.privatechat.app.data.model.Message
import com.privatechat.app.databinding.ItemMessageIncomingBinding
import com.privatechat.app.databinding.ItemMessageOutgoingBinding
import com.privatechat.app.databinding.ItemMessageSystemBinding
import com.privatechat.app.utils.PresenceFormatter
import kotlin.math.abs

class MessageAdapter(private val currentUser: String) :
    ListAdapter<Message, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    // Reported up to ChatActivity — it owns what each gesture does so the
    // adapter stays free of any reply-mode/menu/reaction-bar state.
    var onSwipeReply: ((Message) -> Unit)? = null
    var onMessageTap: ((Message, View) -> Unit)? = null
    var onMessageLongPress: ((Message, View) -> Unit)? = null

    // Live custom nicknames (username -> nickname), kept in sync by
    // ChatActivity from ChatRepository.onNicknamesChanged. Re-renders
    // bound rows on change so reply-preview sender labels update
    // instantly for both users, without waiting for the message list
    // itself to change.
    var nicknames: Map<String, String> = emptyMap()
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    override fun getItemViewType(position: Int): Int {
        val message = getItem(position)
        return when {
            message.type == "system" -> VIEW_TYPE_SYSTEM
            message.name == currentUser -> VIEW_TYPE_OUTGOING
            else -> VIEW_TYPE_INCOMING
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_OUTGOING -> OutgoingHolder(ItemMessageOutgoingBinding.inflate(inflater, parent, false))
            VIEW_TYPE_SYSTEM -> SystemHolder(ItemMessageSystemBinding.inflate(inflater, parent, false))
            else -> IncomingHolder(ItemMessageIncomingBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position)
        when (holder) {
            is OutgoingHolder -> holder.bind(message)
            is IncomingHolder -> holder.bind(message)
            is SystemHolder -> holder.bind(message)
        }
    }

    private fun displayText(message: Message): String = previewText(message, currentUser)

    // Sender label used inside a reply preview ("You" / the other user's
    // display name), reflecting a live custom nickname if one is set,
    // falling back to the original Kat/Kitty default otherwise.
    private fun senderLabel(sender: String): String =
        if (sender == currentUser) "You" else Nicknames.resolve(sender, nicknames)

    private fun bindReactionsBadge(message: Message, badge: android.widget.TextView) {
        val summary = message.reactionsSummary()
        if (summary != null) {
            badge.text = summary
            badge.visibility = View.VISIBLE
        } else {
            badge.visibility = View.GONE
        }
    }

    // Single touch listener that resolves a gesture into exactly one of:
    // plain tap (react bar), 2s long-press (action menu), or a left-to-right
    // swipe past ~30dp (reply) — mirroring WhatsApp/Messenger's own gesture
    // disambiguation so none of the three ever fire together.
    private fun attachGestures(root: View, bubble: View, replyIcon: View, message: Message) {
        val density = root.resources.displayMetrics.density
        val swipeThreshold = 30 * density
        val maxSwipe = 72 * density
        val touchSlop = ViewConfiguration.get(root.context).scaledTouchSlop

        var downX = 0f
        var downY = 0f
        var dragging = false
        var swipeArmed = false
        var longPressFired = false

        val longPressRunnable = Runnable {
            longPressFired = true
            onMessageLongPress?.invoke(message, bubble)
        }

        root.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    dragging = false
                    swipeArmed = false
                    longPressFired = false
                    v.handler?.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && !longPressFired &&
                        (abs(dx) > touchSlop || abs(dy) > touchSlop) && dx > 0 && dx > abs(dy)
                    ) {
                        v.handler?.removeCallbacks(longPressRunnable)
                        dragging = true
                    }
                    if (dragging) {
                        val clamped = dx.coerceIn(0f, maxSwipe)
                        bubble.translationX = clamped
                        val progress = (clamped / swipeThreshold).coerceIn(0f, 1f)
                        replyIcon.alpha = progress
                        replyIcon.scaleX = 0.6f + 0.4f * progress
                        replyIcon.scaleY = 0.6f + 0.4f * progress
                        if (clamped >= swipeThreshold && !swipeArmed) {
                            swipeArmed = true
                            vibrateLightly(v)
                        } else if (clamped < swipeThreshold && swipeArmed) {
                            swipeArmed = false
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.handler?.removeCallbacks(longPressRunnable)
                    if (dragging) {
                        // Spring the bubble back to rest; OvershootInterpolator
                        // gives the little bounce-past-zero "spring" feel
                        // without pulling in a physics library.
                        bubble.animate().translationX(0f)
                            .setInterpolator(OvershootInterpolator(1.5f))
                            .setDuration(220)
                            .start()
                        replyIcon.animate().alpha(0f).setDuration(180).start()
                        if (swipeArmed) onSwipeReply?.invoke(message)
                    } else if (!longPressFired && event.actionMasked == MotionEvent.ACTION_UP) {
                        onMessageTap?.invoke(message, bubble)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun vibrateLightly(view: View) {
        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (view.context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                view.context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(15)
            }
        } catch (e: SecurityException) {
            // VIBRATE is a normal permission but still must be declared in
            // the manifest to be granted; if it isn't, skip the haptic
            // rather than crash the swipe gesture over it.
        }
    }

    // Caps a bubble's width to ~72% of the screen once its natural
    // (wrap_content) width is known, mimicking WhatsApp's bubble sizing.
    // LinearLayout has no built-in maxWidth, so this measures the bubble
    // after layout and clamps it down only if it actually overflowed.
    private fun capBubbleWidth(bubble: View) {
        bubble.doOnPreDraw {
            val maxWidth = (bubble.resources.displayMetrics.widthPixels * 0.72f).toInt()
            if (bubble.width > maxWidth) {
                bubble.layoutParams = bubble.layoutParams.apply { width = maxWidth }
                bubble.requestLayout()
            }
        }
    }

    private fun bindReplyPreview(
        message: Message,
        replyPreview: View,
        replySender: android.widget.TextView,
        replyText: android.widget.TextView
    ) {
        if (message.hasReply()) {
            replyPreview.visibility = View.VISIBLE
            replySender.text = senderLabel(message.replySender.orEmpty())
            replyText.text = message.replyText.orEmpty()
        } else {
            replyPreview.visibility = View.GONE
        }
    }

    inner class OutgoingHolder(private val binding: ItemMessageOutgoingBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(message: Message) {
            binding.messageText.text = displayText(message)
            binding.messageTime.text = PresenceFormatter.messageTime(message.time) +
                if (message.edited) " (edited)" else ""
            binding.messageTick.text = if (message.seen) "✔✔" else "✔"
            bindReplyPreview(message, binding.replyPreview, binding.replyPreviewSender, binding.replyPreviewText)
            bindReactionsBadge(message, binding.reactionsBadge)
            capBubbleWidth(binding.bubbleContainer)
            binding.bubbleContainer.translationX = 0f
            binding.swipeReplyIcon.alpha = 0f
            attachGestures(itemView, binding.bubbleContainer, binding.swipeReplyIcon, message)
        }
    }

    inner class IncomingHolder(private val binding: ItemMessageIncomingBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(message: Message) {
            binding.messageText.text = displayText(message)
            binding.messageTime.text = PresenceFormatter.messageTime(message.time) +
                if (message.edited) " (edited)" else ""
            bindReplyPreview(message, binding.replyPreview, binding.replyPreviewSender, binding.replyPreviewText)
            bindReactionsBadge(message, binding.reactionsBadge)
            capBubbleWidth(binding.bubbleContainer)
            binding.bubbleContainer.translationX = 0f
            binding.swipeReplyIcon.alpha = 0f
            attachGestures(itemView, binding.bubbleContainer, binding.swipeReplyIcon, message)
        }
    }

    // No gestures, no bubble — just centered, light-gray informational
    // text (vanish mode on/off, etc.), matching WhatsApp's own system
    // notice style.
    inner class SystemHolder(private val binding: ItemMessageSystemBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(message: Message) {
            binding.systemMessageText.text = message.text
        }
    }

    companion object {
        private const val VIEW_TYPE_OUTGOING = 1
        private const val VIEW_TYPE_INCOMING = 2
        private const val VIEW_TYPE_SYSTEM = 3
        private const val LONG_PRESS_MS = 800L

        // Exposed so ChatActivity can build the same short preview text for
        // the reply bar / popup menu, without duplicating the "deleted /
        // voice / gif" special-casing. Pass viewerUser to get the
        // "You unsent..." vs "This message was unsent" split; omit it (e.g.
        // for a reply snapshot) to get the generic fallback.
        fun previewText(message: Message, viewerUser: String? = null): String = when {
            message.deleted -> when {
                viewerUser == null -> "🚫 Message deleted"
                message.name == viewerUser -> "You unsent this message"
                else -> "This message was unsent"
            }
            message.isVoice() -> "🎤 Voice message"
            message.isGif() -> "🎞️ GIF"
            else -> message.text
        }

        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Message>() {
            override fun areItemsTheSame(oldItem: Message, newItem: Message) = oldItem.key == newItem.key
            override fun areContentsTheSame(oldItem: Message, newItem: Message) = oldItem == newItem
        }
    }
}
