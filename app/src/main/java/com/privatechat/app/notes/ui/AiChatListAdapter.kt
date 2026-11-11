package com.privatechat.app.notes.ui

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.privatechat.app.R
import com.privatechat.app.notes.data.AiChat

class AiChatListAdapter(
    private val chats: List<Pair<AiChat, String?>>,
    private val onOpen: (AiChat) -> Unit,
    private val onMore: (AiChat) -> Unit
) : RecyclerView.Adapter<AiChatListAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.aiChatItemTitle)
        val preview: TextView = view.findViewById(R.id.aiChatItemPreview)
        val time: TextView = view.findViewById(R.id.aiChatItemTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ai_chat, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = chats.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (chat, preview) = chats[position]
        holder.title.text = chat.title
        holder.preview.text = preview.orEmpty()
        holder.time.text = DateUtils.getRelativeTimeSpanString(
            chat.updatedAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        ).toString()
        holder.itemView.setOnClickListener { onOpen(chat) }
        holder.itemView.setOnLongClickListener {
            onMore(chat)
            true
        }
    }
}
