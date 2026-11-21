package app.zimly.backup.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
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
 * When a waiting sync tries again. Android treats the time as "no earlier than" (Doze, a missing
 * network or a rarely used app can hold a try back), so it is a clock time, and once it passes
 * the text says what the sync is still waiting for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RetryTextTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val defaultZone = TimeZone.getDefault()
    private val defaultLocale = Locale.getDefault()
    private val today = ZonedDateTime.of(2026, 11, 21, 15, 0, 0, 0, ZoneOffset.UTC)

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
    fun `a try later today shows its clock time`() {
        assertEquals("Next try after 3:42\u202FPM", nextTryText(context, millis(today.withMinute(42)), millis(today)))
    }

    @Test
    fun `a try on another day also shows its date`() {
        val tomorrow = today.plusDays(1).withHour(1).withMinute(5)

        assertEquals("Next try after Nov 22, 1:05\u202FAM", nextTryText(context, millis(tomorrow), millis(today)))
    }

    @Test
    fun `before its time, a waiting sync shows when it tries again`() {
        val text = waitingText(context, nextTryAt = millis(today.withMinute(42)), now = millis(today), hasNetwork = true)

        assertEquals("Next try after 3:42\u202FPM", text)
    }

    @Test
    fun `past its time without a network, it waits for one`() {
        val text = waitingText(context, nextTryAt = millis(today), now = millis(today.withMinute(1)), hasNetwork = false)

        assertEquals("Waiting for network", text)
    }

    @Test
    fun `past its time with a network, it waits for Android to start it`() {
        val text = waitingText(context, nextTryAt = millis(today), now = millis(today.withMinute(1)), hasNetwork = true)

        assertEquals("Waiting for Android to start the next try", text)
    }

    private fun millis(time: ZonedDateTime): Long = time.toInstant().toEpochMilli()
}
