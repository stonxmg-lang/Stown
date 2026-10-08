package com.stown.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Foreground service: runs downloads one after another so they survive leaving the app. */
class DownloadService : Service() {

    private val executor = Executors.newSingleThreadExecutor()

    private val wakeLock: PowerManager.WakeLock by lazy {
        (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stown:download")
    }
    private val pending = AtomicInteger(0)
    private val resultIds = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()
        // startForeground must follow startForegroundService quickly
        ServiceCompat.startForeground(
            this,
            FOREGROUND_ID,
            progressNotification(getString(R.string.preparing), -1),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        val url = intent?.getStringExtra(EXTRA_URL)
        val mode = Mode.from(intent?.getStringExtra(EXTRA_MODE))

        if (url == null) {
            if (pending.get() == 0) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }

        pending.incrementAndGet()
        executor.execute { process(url, mode) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private fun process(url: String, mode: Mode) {
        try {
            publish(-1, getString(R.string.preparing))
            wakeLock.acquire()

            if (!StownApp.awaitReady(120_000)) {
                throw IllegalStateException(StownApp.initError ?: "فشل تهيئة المحرك")
            }

            var lastPct = -2
            val file = Downloader(this).run(url, mode) { pct, _ ->
                if (pct != lastPct) {
                    lastPct = pct
                    publish(pct, getString(R.string.downloading))
                }
            }

            publish(-1, getString(R.string.saving))
            MediaSaver.save(this, file, mode)
            file.parentFile?.deleteRecursively()
            notifyResult(true, file.name)
        } catch (e: Throwable) {
            notifyResult(false, shortError(e))
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
            if (pending.decrementAndGet() == 0) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun publish(pct: Int, text: String) {
        DownloadBus.post(DownloadBus.State(true, pct, text))
        notificationManager().notify(FOREGROUND_ID, progressNotification(text, pct))
    }

    private fun progressNotification(text: String, pct: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Stown")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (pct < 0) builder.setProgress(0, 0, true) else builder.setProgress(100, pct, false)
        return builder.build()
    }

    private fun notifyResult(ok: Boolean, detail: String) {
        val title = getString(if (ok) R.string.done else R.string.failed)
        val notification = NotificationCompat.Builder(this, CHANNEL_RESULT)
            .setSmallIcon(
                if (ok) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_notify_error
            )
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setAutoCancel(true)
            .build()
        notificationManager().notify(1000 + resultIds.incrementAndGet(), notification)
        DownloadBus.post(DownloadBus.State(false, if (ok) 100 else 0, "$title: $detail"))
    }

    private fun shortError(e: Throwable): String {
        val msg = e.message ?: e.toString()
        val lines = msg.lines()
        val line = lines.lastOrNull { it.contains("ERROR", ignoreCase = true) }
            ?: lines.firstOrNull { it.isNotBlank() }
            ?: msg
        return line.take(220)
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = notificationManager()
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROGRESS,
                getString(R.string.channel_progress),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULT,
                getString(R.string.channel_result),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_MODE = "mode"
        private const val CHANNEL_PROGRESS = "progress"
        private const val CHANNEL_RESULT = "result"
        private const val FOREGROUND_ID = 1

        fun start(context: Context, url: String, mode: Mode) {
            val intent = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_MODE, mode.key)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
