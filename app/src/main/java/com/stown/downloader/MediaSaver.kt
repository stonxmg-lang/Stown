package com.stown.downloader

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException

/** Saves into Movies/Stown (video) or Music/Stown (audio) so gallery and music apps see it. */
object MediaSaver {

    fun save(context: Context, file: File, mode: Mode): String {
        val mime = mimeOf(file, mode)
        return if (Build.VERSION.SDK_INT >= 29) {
            saveScoped(context, file, mode, mime)
        } else {
            saveLegacy(context, file, mode, mime)
        }
    }

    private fun folder(mode: Mode): String =
        if (mode == Mode.VIDEO) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC

    private fun mimeOf(file: File, mode: Mode): String {
        val fromExt = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
        return fromExt ?: if (mode == Mode.VIDEO) "video/mp4" else "audio/mpeg"
    }

    private fun saveScoped(context: Context, file: File, mode: Mode, mime: String): String {
        val resolver = context.contentResolver
        val collection = if (mode == Mode.VIDEO) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${folder(mode)}/Stown")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values)
            ?: throw IOException("تعذر إنشاء الملف في المعرض")

        try {
            val out = resolver.openOutputStream(uri)
                ?: throw IOException("تعذر فتح الملف للكتابة")
            out.use { stream -> file.inputStream().use { it.copyTo(stream) } }

            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return "${folder(mode)}/Stown/${file.name}"
    }

    private fun saveLegacy(context: Context, file: File, mode: Mode, mime: String): String {
        val base = Environment.getExternalStoragePublicDirectory(folder(mode))
        val dir = File(base, "Stown").apply { mkdirs() }
        val target = File(dir, file.name)
        file.copyTo(target, overwrite = true)
        MediaScannerConnection.scanFile(
            context,
            arrayOf(target.absolutePath),
            arrayOf(mime),
            null
        )
        return target.absolutePath
    }
}
