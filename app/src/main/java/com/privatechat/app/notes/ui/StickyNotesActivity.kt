package com.privatechat.app.notes.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.privatechat.app.R
import com.privatechat.app.notes.data.NotesRepository
import com.privatechat.app.notes.data.StickyNote
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** In-app sticky notes with pastel colors. Persisted in Room; no overlay permission used. */
class StickyNotesActivity : AppCompatActivity() {

    private val repo by lazy { NotesRepository(this) }

    companion object {
        val COLORS = mapOf(
            "pink" to "#FCE4EC",
            "lavender" to "#EDE7F6",
            "mint" to "#E8F5E9",
            "cream" to "#FFF8E1",
            "sky" to "#E3F2FD"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sticky)
        findViewById<View>(R.id.stickyBack).setOnClickListener { finish() }
        findViewById<View>(R.id.stickyAdd).setOnClickListener { showEditor(null) }

        val list = findViewById<RecyclerView>(R.id.stickyList)
        val empty = findViewById<TextView>(R.id.stickyEmpty)
        list.layoutManager = GridLayoutManager(this, 2)
        val adapter = object : RecyclerView.Adapter<SVH>() {
            var items: List<StickyNote> = emptyList()
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SVH {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_sticky, parent, false)
                return SVH(v)
            }
            override fun getItemCount() = items.size
            override fun onBindViewHolder(holder: SVH, position: Int) {
                val s = items[position]
                holder.text.text = s.text
                holder.pin.visibility = if (s.pinned) View.VISIBLE else View.GONE
                holder.itemView.setBackgroundColor(Color.parseColor(COLORS[s.color] ?: COLORS["pink"]))
                holder.itemView.setOnClickListener { showEditor(s) }
            }
        }
        list.adapter = adapter

        lifecycleScope.launch {
            repo.observeStickies().collectLatest { items ->
                adapter.items = items
                adapter.notifyDataSetChanged()
                empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    class SVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.stickyText)
        val pin: TextView = v.findViewById(R.id.stickyPin)
    }

    private fun showEditor(existing: StickyNote?) {
        val input = EditText(this).apply {
            hint = getString(R.string.notes_sticky_hint)
            minLines = 3
            setText(existing?.text.orEmpty())
        }
        var chosenColor = existing?.color ?: "pink"
        var pinned = existing?.pinned ?: false

        val colorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        COLORS.forEach { (name, hex) ->
            val swatch = TextView(this@StickyNotesActivity).apply {
                text = "●"
                textSize = 34f
                setTextColor(Color.parseColor(hex.replace("#F", "#E").replace("#E8", "#C8")))
                setPadding(12, 4, 12, 4)
                setOnClickListener { chosenColor = name; Toast.makeText(this@StickyNotesActivity, name, Toast.LENGTH_SHORT).show() }
            }
            colorRow.addView(swatch)
        }

        val pinRow = TextView(this).apply {
            text = getString(if (pinned) R.string.notes_unpin else R.string.notes_pin)
            setPadding(8, 16, 8, 8)
            setOnClickListener {
                pinned = !pinned
                text = getString(if (pinned) R.string.notes_unpin else R.string.notes_pin)
            }
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(input)
            addView(colorRow)
            if (existing != null) addView(pinRow)
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) getString(R.string.notes_sticky_new) else getString(R.string.notes_sticky_edit))
            .setView(container)
            .setPositiveButton(getString(R.string.notes_save)) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    repo.saveSticky(
                        (existing ?: StickyNote()).copy(text = text, color = chosenColor, pinned = pinned)
                    )
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (existing != null) {
            builder.setNeutralButton(getString(R.string.notes_delete)) { _, _ ->
                lifecycleScope.launch { repo.deleteSticky(existing) }
            }
        }
        builder.show()
    }
}
