package app.zimly.backup.sync

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.zimly.backup.data.db.retry.NextTry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone

/**
 * A sync waiting to retry, or one that gave up, says so in a notification while the app is
 * closed: the error, the earliest next try as a clock time, and a Cancel action.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RetryNotificationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cancel: PendingIntent =
        PendingIntent.getBroadcast(context, 0, Intent("cancel"), PendingIntent.FLAG_IMMUTABLE)
    private val defaultZone = TimeZone.getDefault()
    private val defaultLocale = Locale.getDefault()

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(defaultZone)
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun `a waiting sync shows its error and the clock time of its next try`() {
        val notification = retrying()

        assertEquals("Sync failed, will try again", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Next try after 3:42\u202FPM", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(
            "Connection reset\nNext try after 3:42\u202FPM",
            notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString(),
        )
    }

    @Test
    fun `a waiting sync can be cancelled from its notification`() {
        val notification = retrying()

        assertEquals(listOf("Cancel"), notification.actions.map { it.title.toString() })
        assertSame(cancel, notification.actions.single().actionIntent)
        assertFalse("dismissable", notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    /**
     * Problems have their own channel at normal importance, so they don't land in the quiet
     * progress channel's Silent section, and hiding the progress bar doesn't hide them.
     */
    @Test
    fun `problems post on their own channel at normal importance`() {
        val notification = retrying()

        assertEquals(RetryNotification.CHANNEL_ID, notification.channelId)
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(RetryNotification.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
    }

    /**
     * Retries come at 1, 2, 4, 8 minutes and on: only the first failure of a streak makes a sound.
     * NotificationCompat silences a notification by having only a group summary alert.
     */
    @Test
    fun `a retry after the first one updates quietly`() {
        assertEquals(Notification.GROUP_ALERT_ALL, retrying(alert = true).groupAlertBehavior)
        assertEquals(Notification.GROUP_ALERT_SUMMARY, retrying(alert = false).groupAlertBehavior)
    }

    @Test
    fun `a sync that gave up shows its error and goes away when tapped`() {
        val notification = RetryNotification.failed(context, "Gave up after 24 hours of failed tries: Connection reset")

        assertEquals("Sync failed", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(
            "Gave up after 24 hours of failed tries: Connection reset",
            notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString(),
        )
        assertTrue("auto cancel", notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals(RetryNotification.CHANNEL_ID, notification.channelId)
        assertEquals(Notification.GROUP_ALERT_ALL, notification.groupAlertBehavior)
    }

    /** A retry at 3:42 PM, shown at 3:00 PM the same day, so the text shows only the time. */
    private fun retrying(alert: Boolean = true): Notification {
        val now = ZonedDateTime.of(2026, 11, 21, 15, 0, 0, 0, ZoneOffset.UTC)
        val at = now.withMinute(42).toInstant().toEpochMilli()
        return RetryNotification.retrying(context, NextTry("Connection reset", at), cancel, alert, now.toInstant().toEpochMilli())
    }
}
