package app.zimly.backup.sync

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import app.zimly.backup.R

/**
 * The ongoing notification of a sync that runs as a foreground job. Running in the foreground
 * keeps Android from stopping the sync at its 10-minute limit for background work.
 */
internal object SyncNotification {

    const val CHANNEL_ID = "sync"
    private const val CHANNEL_NAME = "Sync progress"
    private const val TITLE = "Syncing"
    private const val CANCEL = "Cancel"
    private const val MAX_PROGRESS = 100

    /**
     * Foreground info for a sync: a `dataSync` foreground service showing [percent] (indeterminate
     * while null) and a cancel action that fires [cancel]. [notificationId] must not be 0.
     */
    fun foregroundInfo(
        context: Context,
        notificationId: Int,
        percent: Int?,
        cancel: PendingIntent,
    ): ForegroundInfo {
        require(notificationId != 0) { "A foreground notification id must not be 0" }
        createChannel(context)
        return ForegroundInfo(
            notificationId,
            notification(context, percent, cancel),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    /** Creating a channel that already exists does nothing, so this is safe on every update. */
    fun createChannel(context: Context) {
        val channel = NotificationChannelCompat
            .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(CHANNEL_NAME)
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    private fun notification(context: Context, percent: Int?, cancel: PendingIntent): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(TITLE)
            .setProgress(MAX_PROGRESS, percent ?: 0, percent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(NotificationCompat.Action.Builder(null, CANCEL, cancel).build())
            .build()
}
