package app.zimly.backup.sync

import android.content.Context
import android.text.format.DateUtils
import java.time.Instant
import java.time.ZoneId

/**
 * "Next try after 3:42 PM", with the date when the try isn't on the same day as [now]. A clock
 * time, not a countdown: Android treats the time as "no earlier than", so a countdown could reach
 * zero with nothing happening.
 */
internal fun nextTryText(context: Context, at: Long, now: Long): String {
    // No year: a retry is at most WorkManager's 5-hour backoff cap away.
    val dateFlags = if (sameDay(at, now)) 0 else SHOW_SHORT_DATE
    return "Next try after ${DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_TIME or dateFlags)}"
}

private const val SHOW_SHORT_DATE =
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR

/**
 * What a sync waiting to retry shows: when it tries again, or, once that time has passed, what
 * it is still waiting for. WorkManager waits for a network before it starts the try.
 */
internal fun waitingText(context: Context, nextTryAt: Long, now: Long, hasNetwork: Boolean): String = when {
    now < nextTryAt -> nextTryText(context, nextTryAt, now)
    !hasNetwork -> "Waiting for network"
    else -> "Waiting for Android to start the next try"
}

private fun sameDay(a: Long, b: Long): Boolean {
    val zone = ZoneId.systemDefault()
    return Instant.ofEpochMilli(a).atZone(zone).toLocalDate() == Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
}
