package com.stown.downloader

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/** Popup shown when a link is shared to Stown from another app. */
class ShareActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        val url = UrlUtils.extractUrl(text)
        if (url == null) {
            Toast.makeText(this, R.string.no_link, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContentView(R.layout.activity_share)
        findViewById<TextView>(R.id.shareUrl).text = url

        findViewById<MaterialButton>(R.id.shareVideo).setOnClickListener { begin(url, Mode.VIDEO) }
        findViewById<MaterialButton>(R.id.shareAudio).setOnClickListener { begin(url, Mode.AUDIO) }
        findViewById<MaterialButton>(R.id.shareCancel).setOnClickListener { finish() }

        Perms.requestRuntime(this)
    }

    private fun begin(url: String, mode: Mode) {
        val wasBusy = DownloadService.isBusy()
        DownloadService.start(this, url, mode)
        Toast.makeText(
            this,
            if (wasBusy) R.string.queued else R.string.started,
            Toast.LENGTH_SHORT
        ).show()
        finish()
    }
}
