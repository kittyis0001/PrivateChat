package com.privatechat.app.notes.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.privatechat.app.R
import android.view.LayoutInflater
import android.view.ViewGroup

/** Predefined note templates. Tapping one opens the editor pre-filled; nothing is overwritten. */
class TemplatesActivity : AppCompatActivity() {

    data class Template(val titleRes: Int, val contentRes: Int)

    private val templates = listOf(
        Template(R.string.tpl_daily_journal, R.string.tpl_daily_journal_body),
        Template(R.string.tpl_study, R.string.tpl_study_body),
        Template(R.string.tpl_shopping, R.string.tpl_shopping_body),
        Template(R.string.tpl_todo, R.string.tpl_todo_body),
        Template(R.string.tpl_work, R.string.tpl_work_body),
        Template(R.string.tpl_diary, R.string.tpl_diary_body),
        Template(R.string.tpl_meeting, R.string.tpl_meeting_body),
        Template(R.string.tpl_important, R.string.tpl_important_body)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_templates)
        findViewById<View>(R.id.templatesBack).setOnClickListener { finish() }

        val list = findViewById<RecyclerView>(R.id.templatesList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = object : RecyclerView.Adapter<TVH>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TVH {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_template, parent, false)
                return TVH(v)
            }
            override fun getItemCount() = templates.size
            override fun onBindViewHolder(holder: TVH, position: Int) {
                val t = templates[position]
                holder.title.text = getString(t.titleRes)
                holder.preview.text = getString(t.contentRes).replace("\n", " ").take(90)
                holder.itemView.setOnClickListener {
                    val intent = Intent(this@TemplatesActivity, NoteEditorActivity::class.java)
                        .putExtra(NoteEditorActivity.EXTRA_TEMPLATE_TITLE, getString(t.titleRes))
                        .putExtra(NoteEditorActivity.EXTRA_TEMPLATE_CONTENT, getString(t.contentRes))
                    startActivity(intent)
                    finish()
                }
            }
        }
    }

    class TVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.templateTitle)
        val preview: TextView = v.findViewById(R.id.templatePreview)
    }
}
