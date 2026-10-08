package com.stown.downloader

enum class Mode(val key: String) {
    VIDEO("video"),
    AUDIO("audio");

    companion object {
        fun from(key: String?): Mode = values().firstOrNull { it.key == key } ?: VIDEO
    }
}
