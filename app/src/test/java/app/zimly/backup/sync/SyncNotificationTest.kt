package app.zimly.backup.sync

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncNotificationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cancel: PendingIntent =
        PendingIntent.getBroadcast(context, 0, Intent("cancel"), PendingIntent.FLAG_IMMUTABLE)

    @Test
    fun `runs as a data sync foreground service under the given id`() {
        val info = SyncNotification.foregroundInfo(context, NOTIFICATION_ID, percent = 42, cancel)

        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
        assertEquals(NOTIFICATION_ID, info.notificationId)
    }

    @Test
    fun `shows ongoing progress on the sync channel`() {
        val notification = notification(percent = 42)

        assertTrue("ongoing", notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(SyncNotification.CHANNEL_ID, notification.channelId)
        assertEquals(100, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(42, notification.extras.getInt(Notification.EXTRA_PROGRESS))
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
    }

    @Test
    fun `progress is indeterminate before the first percent`() {
        val notification = notification(percent = null)

        assertTrue(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
    }

    @Test
    fun `offers a cancel action`() {
        val action = notification(percent = 42).actions.single()

        assertEquals("Cancel", action.title)
        assertSame(cancel, action.actionIntent)
    }

    @Test
    fun `creates its channel at low importance`() {
        notification(percent = null)

        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(SyncNotification.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    private fun notification(percent: Int?): Notification =
        SyncNotification.foregroundInfo(context, NOTIFICATION_ID, percent, cancel).notification

    private companion object {
        const val NOTIFICATION_ID = 7
    }
}
