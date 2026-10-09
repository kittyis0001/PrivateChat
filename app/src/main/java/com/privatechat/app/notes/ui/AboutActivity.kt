package com.privatechat.app.notes.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.privatechat.app.BuildConfig
import com.privatechat.app.R

/** Notes-facing About screen. Describes the Notes product only. */
class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        findViewById<View>(R.id.aboutBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.aboutBody).text = getString(R.string.notes_about_body)
        findViewById<TextView>(R.id.aboutVersion).text =
            getString(R.string.notes_about_version, BuildConfig.VERSION_NAME)
    }
}
