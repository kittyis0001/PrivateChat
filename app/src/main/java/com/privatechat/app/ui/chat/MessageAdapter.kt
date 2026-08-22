package com.privatechat.app.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.doOnPreDraw
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import android.text.method.LinkMovementMethod
import com.privatechat.app.data.model.Message
import com.privatechat.app.databinding.ItemMessageIncomingBinding
import com.privatechat.app.databinding.ItemMessageOutgoingBinding
import com.privatechat.app.utils.PresenceFormatter

class MessageAdapter(private val currentUser: String) :
    ListAdapter<Message, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    // Long-press-to-reply hook. ChatActivity sets this to enter reply mode
    // for the pressed message; the adapter itself has no notion of "reply
    // mode", it just reports the gesture upward.
    var onMessageLongPress: ((Message) -> Unit)? = null

    override fun getItemViewType(position: Int): Int =
        if (getItem(position).name == currentUser) VIEW_TYPE_OUTGOING else VIEW_TYPE_INCOMING

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_OUTGOING) {
            OutgoingHolder(ItemMessageOutgoingBinding.inflate(inflater, parent, false))
        } else {
            IncomingHolder(ItemMessageIncomingBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position)
        when (holder) {
            is OutgoingHolder -> holder.bind(message)
            is IncomingHolder -> holder.bind(message)
        }
    }

    private fun displayText(message: Message): String = previewText(message)

    // Sender label used inside a reply preview ("You" / the other user's
    // display name), matching the same Kitty/Kat naming ChatActivity uses
    // for the header.
    private fun senderLabel(sender: String): String = when {
        sender == currentUser -> "You"
        sender == "katis1" -> "Kat"
        else -> "Kitty"
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
            binding.messageTime.text = PresenceFormatter.messageTime(message.time)
            binding.messageTick.text = if (message.seen) "✔✔" else "✔"
            bindReplyPreview(message, binding.replyPreview, binding.replyPreviewSender, binding.replyPreviewText)
            capBubbleWidth(binding.bubbleContainer)
            itemView.setOnLongClickListener { onMessageLongPress?.invoke(message); true }
        }
    }

    inner class IncomingHolder(private val binding: ItemMessageIncomingBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(message: Message) {
            binding.messageText.text = displayText(message)
            binding.messageTime.text = PresenceFormatter.messageTime(message.time)
            bindReplyPreview(message, binding.replyPreview, binding.replyPreviewSender, binding.replyPreviewText)
            capBubbleWidth(binding.bubbleContainer)
            itemView.setOnLongClickListener { onMessageLongPress?.invoke(message); true }
        }
    }

    companion object {
        private const val VIEW_TYPE_OUTGOING = 1
        private const val VIEW_TYPE_INCOMING = 2

        // Exposed so ChatActivity can build the same short preview text for
        // the reply bar above the input, without duplicating the "deleted /
        // voice / gif" special-casing.
        fun previewText(message: Message): String = when {
            message.deleted -> "🚫 Message deleted"
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
