package com.privatechat.app.notes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.privatechat.app.R
import com.privatechat.app.notes.util.RichText

/** One row of the AI chat: a user message, an AI reply, a live (streaming) reply, or the typing indicator. */
data class AiChatMessage(
    val text: String,
    val isUser: Boolean,
    val image: ByteArray? = null,
    val isTyping: Boolean = false,
    val isLive: Boolean = false
)

class AiChatAdapter(
    private val messages: List<AiChatMessage>,
    private val onRegenerate: (Int) -> Unit = {},
    private val onListen: (Int) -> Unit = {},
    private val onImageLongPress: (ByteArray) -> Unit = {}
) : RecyclerView.Adapter<AiChatAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val bubble: LinearLayout = view.findViewById(R.id.chatMessageBubble)
        val imageView: ImageView = view.findViewById(R.id.chatMessageImage)
        val textView: TextView = view.findViewById(R.id.chatMessageText)
        val actionsRow: LinearLayout = view.findViewById(R.id.chatMessageActions)
        val regenerate: TextView = view.findViewById(R.id.chatMessageRegenerate)
        val listen: TextView = view.findViewById(R.id.chatMessageListen)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ai_chat_message, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = messages.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val msg = messages[position]
        val context = holder.itemView.context
        // User messages sit on the right with the gradient bubble,
        // AI replies on the left with the plain surface bubble.
        val bubbleParams = holder.bubble.layoutParams as LinearLayout.LayoutParams
        bubbleParams.gravity = if (msg.isUser) android.view.Gravity.END else android.view.Gravity.START
        holder.bubble.layoutParams = bubbleParams
        holder.bubble.setBackgroundResource(
            if (msg.isUser) R.drawable.bg_bubble_outgoing else R.drawable.bg_bubble_incoming
        )
        holder.textView.setTextColor(
            ContextCompat.getColor(
                context,
                if (msg.isUser) android.R.color.white else R.color.textPrimary
            )
        )
        if (msg.image != null) {
            holder.imageView.setImageBitmap(
                BitmapFactory.decodeByteArray(msg.image, 0, msg.image.size)
            )
            holder.imageView.visibility = View.VISIBLE
        } else {
            holder.imageView.visibility = View.GONE
        }
        holder.textView.text = if (msg.isUser || msg.isTyping || msg.isLive) {
            msg.text
        } else {
            // The AI's **bold** markers render as real bold text.
            RichText.toSpanned(msg.text)
        }
        // Actions only on a finished AI reply. "Rewrite" only on the
        // last one, so regenerating never rewrites an old answer.
        val isFinishedAi = !msg.isUser && !msg.isTyping && !msg.isLive
        holder.actionsRow.visibility = if (isFinishedAi) View.VISIBLE else View.GONE
        holder.regenerate.visibility =
            if (isFinishedAi && position == messages.lastIndex) View.VISIBLE else View.GONE
        holder.regenerate.setOnClickListener { onRegenerate(position) }
        holder.listen.setOnClickListener { onListen(position) }
        // Long-press: a picture saves to the Gallery, a text message copies.
        holder.itemView.setOnLongClickListener {
            if (msg.isTyping || msg.isLive) return@setOnLongClickListener false
            if (msg.image != null) {
                onImageLongPress(msg.image)
            } else {
                val clipboard =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("AI chat", msg.text))
                Toast.makeText(context, R.string.notes_ai_chat_copied, Toast.LENGTH_SHORT).show()
            }
            true
        }
    }
}
