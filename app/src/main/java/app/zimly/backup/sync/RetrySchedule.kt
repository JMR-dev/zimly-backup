package app.zimly.backup.sync

import androidx.work.WorkRequest
import java.time.Duration
import kotlin.math.pow

/** The sync work request's exponential backoff: the first retry waits this long. */
internal val SYNC_BACKOFF: Duration = Duration.ofMinutes(1)

/**
 * The earliest time, in epoch milliseconds, of the next try of a sync whose run failed at [now].
 * [runAttemptCount] is the worker's: the runs before this one.
 *
 * WorkManager 2.11.1 (WorkSpec.calculateNextRunTime) waits the backoff times 2^(n - 1), where n
 * is its own count, which already includes this run, capped at [WorkRequest.MAX_BACKOFF_MILLIS],
 * from when it reschedules the work. That is the backoff times 2^[runAttemptCount] from about
 * [now]. It is the earliest start, not a promise: Doze or a missing network can hold it back.
 */
internal fun nextTryAt(now: Long, runAttemptCount: Int): Long {
    // A double, so a large count saturates instead of overflowing; the cap then applies.
    val wait = SYNC_BACKOFF.toMillis() * 2.0.pow(runAttemptCount)
    return now + wait.coerceAtMost(WorkRequest.MAX_BACKOFF_MILLIS.toDouble()).toLong()
}
