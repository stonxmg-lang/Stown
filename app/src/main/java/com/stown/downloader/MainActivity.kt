package com.stown.downloader

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doOnTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class MainActivity : AppCompatActivity() {

    private lateinit var urlLayout: TextInputLayout
    private lateinit var urlInput: TextInputEditText
    private lateinit var progress: LinearProgressIndicator
    private lateinit var status: TextView
    private lateinit var btnCancel: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlLayout = findViewById(R.id.urlLayout)
        urlInput = findViewById(R.id.urlInput)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        btnCancel = findViewById(R.id.btnCancel)

        findViewById<MaterialButton>(R.id.btnVideo).setOnClickListener { start(Mode.VIDEO) }
        findViewById<MaterialButton>(R.id.btnAudio).setOnClickListener { start(Mode.AUDIO) }
        findViewById<MaterialButton>(R.id.btnImage).setOnClickListener { start(Mode.IMAGE) }
        findViewById<MaterialButton>(R.id.btnPermissions).setOnClickListener {
            Perms.requestRuntime(this)
            Perms.openOverlaySettings(this)
        }
        btnCancel.setOnClickListener { DownloadService.cancel(this) }

        // One end icon that morphs: paste when empty, clear when filled
        urlLayout.setEndIconMode(TextInputLayout.END_ICON_CUSTOM)
        updateEndIcon(urlInput.text.isNullOrEmpty())
        urlInput.doOnTextChanged { text, _, _, _ ->
            updateEndIcon(text.isNullOrEmpty())
        }
        urlLayout.setEndIconOnClickListener {
            if (urlInput.text.isNullOrEmpty()) pasteUrl() else urlInput.text?.clear()
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

    private fun pasteUrl() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        val url = UrlUtils.extractUrl(text)
        if (url == null) {
            Toast.makeText(this, R.string.clipboard_no_link, Toast.LENGTH_SHORT).show()
            return
        }
        urlInput.setText(url)
        urlInput.setSelection(url.length)
        urlLayout.error = null
    }

    private fun updateEndIcon(empty: Boolean) {
        urlLayout.setEndIconDrawable(if (empty) R.drawable.ic_paste else R.drawable.ic_clear)
        urlLayout.setEndIconContentDescription(
            if (empty) R.string.paste else R.string.clear
        )
    }

    private fun render(state: DownloadBus.State) {
        status.text = if (state.active && state.progress in 0..100) {
            "${state.text} ${state.progress}%"
        } else {
            state.text
        }

        // While a download is running: cancel button visible, input locked gray
        btnCancel.visibility = if (state.active) View.VISIBLE else View.GONE
        urlLayout.isEnabled = !state.active
        urlInput.isEnabled = !state.active
        urlLayout.isEndIconVisible = !state.active

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
