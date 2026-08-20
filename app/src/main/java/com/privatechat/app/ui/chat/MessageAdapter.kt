package com.privatechat.app.ui.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.privatechat.app.data.model.Message
import com.privatechat.app.databinding.ItemMessageIncomingBinding
import com.privatechat.app.databinding.ItemMessageOutgoingBinding
import com.privatechat.app.utils.PresenceFormatter

class MessageAdapter(private val currentUser: String) :
    ListAdapter<Message, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

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

    private fun displayText(message: Message): String = when {
        message.deleted -> "🚫 Message deleted"
        message.isVoice() -> "🎤 Voice message"
        message.isGif() -> "🎞️ GIF"
        else -> message.text
    }

    inner class OutgoingHolder(private val binding: ItemMessageOutgoingBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(message: Message) {
            binding.messageText.text = displayText(message)
            binding.messageTime.text = PresenceFormatter.messageTime(message.time)
            binding.messageTick.text = if (message.seen) "✔✔" else "✔"
        }
    }

    inner class IncomingHolder(private val binding: ItemMessageIncomingBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(message: Message) {
            binding.messageText.text = displayText(message)
            binding.messageTime.text = PresenceFormatter.messageTime(message.time)
        }
    }

    companion object {
        private const val VIEW_TYPE_OUTGOING = 1
        private const val VIEW_TYPE_INCOMING = 2

        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Message>() {
            override fun areItemsTheSame(oldItem: Message, newItem: Message) = oldItem.key == newItem.key
            override fun areContentsTheSame(oldItem: Message, newItem: Message) = oldItem == newItem
        }
    }
}
