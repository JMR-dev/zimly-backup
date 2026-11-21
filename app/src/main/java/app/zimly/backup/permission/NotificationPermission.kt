package app.zimly.backup.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * The permission to ask for before a sync, so its progress notification shows: POST_NOTIFICATIONS
 * on Android 13+ while it isn't granted, otherwise null. A sync runs whether or not it's granted.
 */
fun missingNotificationPermission(context: Context): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    val permission = Manifest.permission.POST_NOTIFICATIONS
    val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    return if (granted) null else permission
}
