package com.stown.downloader

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class MainActivity : AppCompatActivity() {

    private lateinit var urlLayout: TextInputLayout
    private lateinit var urlInput: TextInputEditText
    private lateinit var progress: LinearProgressIndicator
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlLayout = findViewById(R.id.urlLayout)
        urlInput = findViewById(R.id.urlInput)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)

        findViewById<MaterialButton>(R.id.btnVideo).setOnClickListener { start(Mode.VIDEO) }
        findViewById<MaterialButton>(R.id.btnAudio).setOnClickListener { start(Mode.AUDIO) }
        findViewById<MaterialButton>(R.id.btnPermissions).setOnClickListener {
            Perms.requestRuntime(this)
            Perms.openOverlaySettings(this)
        }

        Perms.requestRuntime(this)
    }

    override fun onStart() {
        super.onStart()
        DownloadBus.listener = { render(it) }
        render(DownloadBus.last)
    }

    override fun onStop() {
        DownloadBus.listener = null
        super.onStop()
    }

    private fun start(mode: Mode) {
        val url = UrlUtils.extractUrl(urlInput.text?.toString())
        if (url == null) {
            urlLayout.error = getString(R.string.invalid_url)
            return
        }
        urlLayout.error = null
        Perms.requestRuntime(this)
        DownloadService.start(this, url, mode)
        status.text = getString(R.string.preparing)
    }

    private fun render(state: DownloadBus.State) {
        status.text = state.text
        if (state.text.isEmpty() && !state.active) {
            progress.visibility = View.INVISIBLE
            return
        }
        progress.visibility = View.VISIBLE
        if (state.active && state.progress < 0) {
            progress.isIndeterminate = true
        } else {
            progress.isIndeterminate = false
            progress.setProgressCompat(state.progress, true)
        }
    }
}
