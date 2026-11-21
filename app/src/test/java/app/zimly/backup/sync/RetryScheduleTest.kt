package app.zimly.backup.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * The earliest time of a failed sync's next try, as WorkManager 2.11.1 computes it for the sync's
 * work request: the backoff doubles with each run, from 1 minute, up to WorkManager's 5-hour cap.
 * The worker sees the run count from before its own run started.
 */
class RetryScheduleTest {

    @Test
    fun `the first retry comes a minute after the failed run`() {
        assertEquals(NOW + 1.minutes, nextTryAfter(runAttemptCount = 0))
    }

    @Test
    fun `each run doubles the wait`() {
        assertEquals(NOW + 8.minutes, nextTryAfter(runAttemptCount = 3))
    }

    @Test
    fun `the wait stops growing at five hours`() {
        assertEquals(NOW + 5.hours, nextTryAfter(runAttemptCount = 20))
    }

    @Test
    fun `a huge run count doesn't overflow`() {
        assertEquals(NOW + 5.hours, nextTryAfter(runAttemptCount = Int.MAX_VALUE))
    }

    private fun nextTryAfter(runAttemptCount: Int): Duration =
        nextTryAt(NOW.inWholeMilliseconds, runAttemptCount).milliseconds

    private companion object {
        val NOW = 1_000_000.minutes
    }
}
