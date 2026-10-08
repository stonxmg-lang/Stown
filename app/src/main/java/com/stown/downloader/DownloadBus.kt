package com.stown.downloader

import android.os.Handler
import android.os.Looper

/** Tiny in-process channel so the service can report progress to MainActivity. */
object DownloadBus {

    data class State(val active: Boolean, val progress: Int, val text: String)

    @Volatile
    var last: State = State(false, 0, "")
        private set

    @Volatile
    var listener: ((State) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    fun post(state: State) {
        last = state
        main.post { listener?.invoke(state) }
    }
}
