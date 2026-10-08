package com.stown.downloader

object UrlUtils {
    private val regex = Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE)

    fun extractUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val match = regex.find(text) ?: return null
        return match.value.trimEnd(')', ']', '}', ',', '.', ';', '"', '\'')
    }
}
