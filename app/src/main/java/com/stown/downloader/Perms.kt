package com.stown.downloader

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object Perms {

    /** Notifications (Android 13+) and legacy storage (Android 9 and below). */
    fun requestRuntime(activity: Activity) {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            !granted(activity, Manifest.permission.POST_NOTIFICATIONS)
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT <= 28 &&
            !granted(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        ) {
            needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, needed.toTypedArray(), 100)
        }
    }

    /** "Display over other apps" is a special permission granted from a settings screen. */
    fun openOverlaySettings(activity: Activity) {
        if (!Settings.canDrawOverlays(activity)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${activity.packageName}")
            )
            activity.startActivity(intent)
        }
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
