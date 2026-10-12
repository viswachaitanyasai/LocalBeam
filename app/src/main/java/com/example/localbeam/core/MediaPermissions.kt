package com.example.localbeam.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * One place for the permission model, which changed in Android 13:
 * `READ_MEDIA_VIDEO` replaces `READ_EXTERNAL_STORAGE` for the video library, and
 * `POST_NOTIFICATIONS` becomes necessary for the foreground-service notification
 * to be visible (the service itself still runs without it).
 */
object MediaPermissions {

    /** The runtime permissions needed to list local videos (declare-and-request). */
    val videoLibrary: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    /** `POST_NOTIFICATIONS`, or `null` when the platform does not need it. */
    val notifications: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyArray()
        }

    fun hasVideoAccess(context: Context): Boolean = videoLibrary.all { isGranted(context, it) }

    fun hasNotificationAccess(context: Context): Boolean =
        notifications.isEmpty() || notifications.all { isGranted(context, it) }

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
