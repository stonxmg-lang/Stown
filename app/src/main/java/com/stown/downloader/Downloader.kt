package com.stown.downloader

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

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

        // yt-dlp's Instagram extractor refuses standalone photo posts with
        // "There is no video in this post" by design — for IMAGE mode we catch
        // exactly that case and fetch the photo ourselves from the page.
        if (mode == Mode.IMAGE && "instagram.com" in url) {
            try {
                return runProfiles(url, mode, base, onProgress)
            } catch (e: Exception) {
                if ("no video in this post" !in (e.message ?: "").lowercase()) throw e
                onProgress(-1, "تنزيل الصورة…")
                return instagramPhoto(url, base)
            }
        }
        return runProfiles(url, mode, base, onProgress)
    }

    private fun runProfiles(
        url: String,
        mode: Mode,
        base: File,
        onProgress: (Int, String) -> Unit,
    ): File {
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

    /** yt-dlp can't extract standalone Instagram photos — scrape the image URL ourselves. */
    private fun instagramPhoto(url: String, base: File): File {
        val shortcode = Regex("""/(?:p|reel|reels)/([A-Za-z0-9_-]+)""")
            .find(url)?.groupValues?.get(1)
            ?: throw IllegalStateException("تعذر قراءة رابط انستجرام")
        val page = httpGet("https://www.instagram.com/p/$shortcode/")
        val imageUrl = listOf(
            Regex(""""display_url"\s*:\s*"([^"]+)""""),
            Regex("""property="og:image"\s+content="([^"]+)""""),
            Regex("""content="([^"]+)"\s+property="og:image""""),
        ).firstNotNullOfOrNull { re ->
            re.find(page)?.groupValues?.get(1)
                ?.replace("\\u0026", "&")
                ?.replace("&amp;", "&")
        } ?: throw IllegalStateException("لم أجد الصورة في البوست")

        val clean = imageUrl.substringBefore('?')
        val ext = when {
            clean.endsWith(".png", true) -> "png"
            clean.endsWith(".webp", true) -> "webp"
            else -> "jpg"
        }
        val out = File(base, "instagram_$shortcode.$ext")
        httpDownload(imageUrl, out)
        if (out.length() == 0L) throw IllegalStateException("الصورة التي نزلت فارغة")
        return out
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 20_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        return try {
            if (conn.responseCode != 200) {
                throw IllegalStateException("HTTP ${conn.responseCode} أثناء قراءة البوست")
            }
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    private fun httpDownload(url: String, out: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 120_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Referer", "https://www.instagram.com/")
        try {
            if (conn.responseCode != 200) {
                throw IllegalStateException("HTTP ${conn.responseCode} أثناء تنزيل الصورة")
            }
            conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
    }

    private fun findQjs(): File? {
        // libqjs.so is the QuickJS executable packaged as a native library
        val qjs = File(context.applicationInfo.nativeLibraryDir, "libqjs.so")
        return if (qjs.exists()) qjs else null
    }

    private fun build(url: String, mode: Mode, dir: File, withJs: Boolean): YoutubeDLRequest {
        val request = YoutubeDLRequest(url)
        // Instagram photo posts / carousels need playlist mode to fetch every
        // item; everywhere else a playlist in the link should not all download.
        val instagram = "instagram.com" in url
        if (!(mode == Mode.IMAGE && instagram)) {
            request.addOption("--no-playlist")
        }
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
            Mode.IMAGE -> {
                // Photo posts expose the picture itself as the best "format"
                request.addOption("-f", "best")
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
