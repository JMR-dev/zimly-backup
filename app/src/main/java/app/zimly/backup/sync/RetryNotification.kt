package app.zimly.backup.sync

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.zimly.backup.R
import app.zimly.backup.data.db.retry.NextTry
import app.zimly.backup.permission.missingNotificationPermission

/**
 * The notifications of a sync that failed while no run is going: one waiting to retry, with its
 * error, its next try and a Cancel action, and one that gave up.
 *
 * They use the sync's notification id under [TAG]. Without the tag they would share the id of the
 * run's foreground notification, which WorkManager removes when the run ends, just after the
 * worker posts them.
 */
internal object RetryNotification {

    const val TAG = "sync_retry"
    private const val RETRYING_TITLE = "Sync failed, will try again"
    private const val FAILED_TITLE = "Sync failed"
    private const val CANCEL = "Cancel"

    fun retrying(
        context: Context,
        nextTry: NextTry,
        cancel: PendingIntent,
        now: Long = System.currentTimeMillis(),
    ): Notification {
        val whenText = nextTryText(context, nextTry.at, now)
        return builder(context, RETRYING_TITLE)
            .setContentText(whenText)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${nextTry.lastError}\n$whenText"))
            .addAction(NotificationCompat.Action.Builder(null, CANCEL, cancel).build())
            .build()
    }

    fun failed(context: Context, error: String): Notification =
        builder(context, FAILED_TITLE)
            .setContentText(error)
            .setStyle(NotificationCompat.BigTextStyle().bigText(error))
            .setAutoCancel(true)
            .build()

    /** Shows [notification] under [notificationId], unless the user hasn't allowed notifications. */
    // The permission is checked just above the call, through missingNotificationPermission.
    @SuppressLint("MissingPermission")
    fun post(context: Context, notificationId: Int, notification: Notification) {
        if (missingNotificationPermission(context) != null) return
        NotificationManagerCompat.from(context).notify(TAG, notificationId, notification)
    }

    fun dismiss(context: Context, notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(TAG, notificationId)
    }

    private fun builder(context: Context, title: String): NotificationCompat.Builder {
        SyncNotification.createChannel(context)
        return NotificationCompat.Builder(context, SyncNotification.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setOnlyAlertOnce(true)
            .apply { openApp(context)?.let { setContentIntent(it) } }
    }

    /** Tapping the notification opens the app, where the sync screen shows the same. */
    private fun openApp(context: Context): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
}
