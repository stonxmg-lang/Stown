package com.stown.downloader

enum class Mode(val key: String) {
    VIDEO("video"),
    AUDIO("audio"),
    IMAGE("image");

    companion object {
        fun from(key: String?): Mode = entries.firstOrNull { it.key == key } ?: VIDEO
    }
}
