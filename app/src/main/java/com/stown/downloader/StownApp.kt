package com.stown.downloader

import android.app.Application
import android.os.SystemClock
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlin.concurrent.thread

class StownApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // First run extracts python/ffmpeg to disk, so keep it off the main thread
        thread(name = "stown-init") {
            try {
                YoutubeDL.getInstance().init(this@StownApp)
                FFmpeg.getInstance().init(this@StownApp)
                // The bundled yt-dlp goes stale fast because YouTube changes
                // constantly; refresh it from GitHub before serving downloads.
                updateYtDlp()
                ready = true
            } catch (e: Throwable) {
                Log.e(TAG, "init failed", e)
                initError = e.message ?: e.toString()
            }
        }
    }

    private fun updateYtDlp() {
        try {
            val status = YoutubeDL.getInstance().updateYoutubeDL(this@StownApp)
            Log.i(TAG, "yt-dlp update status: $status")
        } catch (e: Throwable) {
            // Offline or GitHub unreachable: the bundled yt-dlp still works
            Log.w(TAG, "yt-dlp update failed, using bundled version", e)
        }
    }

    companion object {
        private const val TAG = "StownApp"

        @Volatile
        var ready: Boolean = false

        @Volatile
        var initError: String? = null

        fun awaitReady(timeoutMs: Long): Boolean {
            val end = SystemClock.elapsedRealtime() + timeoutMs
            while (SystemClock.elapsedRealtime() < end) {
                if (ready) return true
                if (initError != null) return false
                Thread.sleep(200)
            }
            return ready
        }
    }
}
