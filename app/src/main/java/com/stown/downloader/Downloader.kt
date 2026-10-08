package com.stown.downloader

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

/** Runs yt-dlp for one link and returns the finished file (in the app cache). */
class Downloader(
    private val context: Context,
    private val onProcessId: (String) -> Unit = {},
) {

    /** A download strategy: optional YouTube extractor args + whether to enable the JS runtime. */
    private data class Profile(val extractorArgs: String?, val useJs: Boolean)

    fun run(url: String, mode: Mode, onProgress: (Int, String) -> Unit): File {
        val base = File(context.cacheDir, "dl/${System.currentTimeMillis()}")
        base.mkdirs()

        val qjs = findQjs()
        val jsAvailable = qjs != null

        // Order matters: the "android" client serves plain direct streams
        // (fast, and audio actually downloads). The default client often gets
        // SABR streams, which YouTube throttles hard and may serve without
        // audio — keep it as a quality fallback, and tv_embedded last because
        // it never needs a PO token.
        val profiles = listOf(
            Profile("youtube:player_client=android", jsAvailable),
            Profile(null, jsAvailable),
            Profile("youtube:player_client=tv_embedded", jsAvailable),
        )

        var lastError: Exception? = null
        // If the bundled yt-dlp doesn't know the JS options, retry once without them
        var jsBroken = false

        for ((index, profile) in profiles.withIndex()) {
            // Each attempt gets its OWN folder. A failed attempt can leave a
            // complete-looking but broken file behind (e.g. video without
            // audio); it must never be picked up as the result.
            val dir = File(base, "a${index + 1}").apply { mkdirs() }
            val request = build(url, mode, dir, profile.useJs && !jsBroken)
            profile.extractorArgs?.let { request.addOption("--extractor-args", it) }
            try {
                execute(request, onProgress)
                val files = dir.listFiles()?.filter { it.isFile && !isTemp(it.name) }.orEmpty()
                val best = files.maxByOrNull { it.length() }
                if (best != null) {
                    // Flatten to base/ and drop the other attempts' leftovers
                    val out = File(base, best.name)
                    if (best.renameTo(out)) {
                        base.listFiles()?.forEach { if (it.isDirectory) it.deleteRecursively() }
                        return out
                    }
                    return best
                }
                lastError = IllegalStateException("لم يتم العثور على الملف بعد التنزيل")
            } catch (e: Exception) {
                lastError = e
                when {
                    looksLikeOptionError(e) -> jsBroken = true
                    isRetryableMediaError(e) -> continue // try the next profile
                    else -> throw e
                }
            }
        }
        base.deleteRecursively()
        throw lastError ?: IllegalStateException("فشل التنزيل")
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

        // Optional members-only support: drop a Netscape-format cookies.txt
        // (exported from a browser logged into the channel) into
        // Android/data/com.stown.downloader/files/ and it's picked up here.
        val cookies = cookiesFile()
        if (cookies != null) {
            request.addOption("--cookies", cookies.absolutePath)
        }

        // Stability + speed: look like a normal browser, survive flaky
        // networks, skip formats that can't actually be fetched, and fetch
        // DASH fragments in parallel.
        request.addOption("--user-agent", USER_AGENT)
        request.addOption("--retries", "10")
        request.addOption("--fragment-retries", "10")
        request.addOption("--extractor-retries", "3")
        request.addOption("--check-formats")
        request.addOption("-N", "4")

        when (mode) {
            Mode.VIDEO -> {
                // Highest quality available, no user choice.
                // Audio must be AAC-in-MP4: Opus-in-MP4 merges play silently on
                // many Android players, and SABR-only audio may download with no
                // sound at all — so prefer m4a (AAC) first, anything second.
                request.addOption(
                    "-f",
                    "bv*+ba[ext=m4a]/bv*+ba[acodec^=mp4a]/bv*+ba/b"
                )
                request.addOption("--merge-output-format", "mp4")
            }
            Mode.AUDIO -> {
                request.addOption("-f", "ba[ext=m4a]/ba/b")
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
        onProcessId(processId)
        YoutubeDL.getInstance().execute(request, processId) { progress, _, line ->
            val pct = if (progress < 0f) -1 else progress.toInt().coerceIn(0, 100)
            onProgress(pct, line ?: "")
        }
    }

    private fun cookiesFile(): File? {
        val dir = context.getExternalFilesDir(null) ?: return null
        val f = File(dir, "cookies.txt")
        return if (f.isFile && f.length() > 0) f else null
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
