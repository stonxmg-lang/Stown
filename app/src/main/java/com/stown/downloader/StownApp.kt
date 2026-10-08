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
                ready = true
            } catch (e: Throwable) {
                Log.e(TAG, "init failed", e)
                initError = e.message ?: e.toString()
            }
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
