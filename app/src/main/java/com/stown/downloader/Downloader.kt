package com.stown.downloader

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

/** Runs yt-dlp for one link and returns the finished file (in the app cache). */
class Downloader(private val context: Context) {

    fun run(url: String, mode: Mode, onProgress: (Int, String) -> Unit): File {
        val dir = File(context.cacheDir, "dl/${System.currentTimeMillis()}")
        dir.mkdirs()

        try {
            execute(build(url, mode, dir, withJs = true), onProgress)
        } catch (e: Exception) {
            // Older bundled yt-dlp may not know the JS runtime options: retry without them
            if (looksLikeOptionError(e)) {
                execute(build(url, mode, dir, withJs = false), onProgress)
            } else {
                throw e
            }
        }

        val files = dir.listFiles()?.filter { it.isFile && !isTemp(it.name) }.orEmpty()
        return files.maxByOrNull { it.length() }
            ?: throw IllegalStateException("لم يتم العثور على الملف بعد التنزيل")
    }

    private fun build(url: String, mode: Mode, dir: File, withJs: Boolean): YoutubeDLRequest {
        val request = YoutubeDLRequest(url)
        request.addOption("--no-playlist")
        request.addOption("-o", File(dir, "%(title).80s [%(id)s].%(ext)s").absolutePath)

        when (mode) {
            Mode.VIDEO -> {
                // Highest quality available, no user choice
                request.addOption("-f", "bv*+ba/b")
                request.addOption("--merge-output-format", "mp4")
            }
            Mode.AUDIO -> {
                request.addOption("-f", "ba/b")
                request.addOption("-x")
                request.addOption("--audio-format", "mp3")
                request.addOption("--audio-quality", "0")
            }
        }

        if (withJs) {
            // libqjs.so is the QuickJS executable packaged as a native library
            val qjs = File(context.applicationInfo.nativeLibraryDir, "libqjs.so")
            if (qjs.exists()) {
                request.addOption("--js-runtimes", "quickjs:${qjs.absolutePath}")
                request.addOption("--remote-components", "ejs:github")
            }
        }
        return request
    }

    private fun execute(request: YoutubeDLRequest, onProgress: (Int, String) -> Unit) {
        val processId = "stown-${System.nanoTime()}"
        YoutubeDL.getInstance().execute(request, processId) { progress, _, line ->
            val pct = if (progress < 0f) -1 else progress.toInt().coerceIn(0, 100)
            onProgress(pct, line ?: "")
        }
    }

    private fun isTemp(name: String): Boolean {
        val n = name.lowercase()
        return n.contains(".part") || n.endsWith(".ytdl") || n.endsWith(".temp") ||
            n.endsWith(".tmp") || n.endsWith(".json")
    }

    private fun looksLikeOptionError(e: Throwable): Boolean {
        val m = (e.message ?: "").lowercase()
        return "no such option" in m || "unrecognized arguments" in m || "unknown option" in m
    }
}
