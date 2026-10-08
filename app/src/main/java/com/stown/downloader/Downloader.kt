package com.stown.downloader

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

/** Runs yt-dlp for one link and returns the finished file (in the app cache). */
class Downloader(private val context: Context) {

    /** A download strategy: optional YouTube extractor args + whether to enable the JS runtime. */
    private data class Profile(val extractorArgs: String?, val useJs: Boolean)

    fun run(url: String, mode: Mode, onProgress: (Int, String) -> Unit): File {
        val dir = File(context.cacheDir, "dl/${System.currentTimeMillis()}")
        dir.mkdirs()

        val qjs = findQjs()
        val jsAvailable = qjs != null

        // YouTube keeps rejecting media requests (HTTP 403). We start with the
        // defaults, then fall back to player clients that bypass SABR/PO-token checks.
        val profiles = listOf(
            Profile(null, jsAvailable),
            Profile("youtube:player_client=android", jsAvailable),
            Profile("youtube:player_client=tv_embedded", jsAvailable),
        )

        var lastError: Exception? = null
        // If the bundled yt-dlp doesn't know the JS options, retry once without them
        var jsBroken = false

        for (profile in profiles) {
            val request = build(url, mode, dir, profile.useJs && !jsBroken)
            profile.extractorArgs?.let { request.addOption("--extractor-args", it) }
            try {
                execute(request, onProgress)
                lastError = null
                break
            } catch (e: Exception) {
                lastError = e
                when {
                    looksLikeOptionError(e) -> jsBroken = true
                    isRetryableMediaError(e) -> continue // try the next player client
                    else -> throw e
                }
            }
        }
        lastError?.let { throw it }

        val files = dir.listFiles()?.filter { it.isFile && !isTemp(it.name) }.orEmpty()
        return files.maxByOrNull { it.length() }
            ?: throw IllegalStateException("لم يتم العثور على الملف بعد التنزيل")
    }

    private fun findQjs(): File? {
        // libqjs.so is the QuickJS executable packaged as a native library
        val qjs = File(context.applicationInfo.nativeLibraryDir, "libqjs.so")
        return if (qjs.exists()) qjs else null
    }

    private fun build(url: String, mode: Mode, dir: File, withJs: Boolean): YoutubeDLRequest {
        val request = YoutubeDLRequest(url)
        request.addOption("--no-playlist")
        request.addOption("-o", File(dir, "%(title).80s [%(id)s].%(ext)s").absolutePath)

        // Stability: look like a normal browser and survive flaky networks
        request.addOption("--user-agent", USER_AGENT)
        request.addOption("--retries", "10")
        request.addOption("--fragment-retries", "10")
        request.addOption("--extractor-retries", "3")

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

        val qjs = findQjs()
        if (withJs && qjs != null) {
            request.addOption("--js-runtimes", "quickjs:${qjs.absolutePath}")
            request.addOption("--remote-components", "ejs:github")
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

    /** 403 from googlevideo / SABR-stream rejections: worth retrying with another player client. */
    private fun isRetryableMediaError(e: Throwable): Boolean {
        val m = (e.message ?: "").lowercase()
        return "403" in m ||
            "unable to download video data" in m ||
            "unable to download webpage" in m ||
            "forbidden" in m
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
