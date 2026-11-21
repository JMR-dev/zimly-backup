package app.zimly.backup.sync

import app.zimly.backup.data.db.retry.NextTry
import app.zimly.backup.data.db.retry.SyncRetryDao
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The failed runs of one remote's sync, kept between runs: how long it has been failing, for
 * [RETRY_WINDOW], and what the sync screen and notification show while it waits to retry.
 */
internal class SyncRetries(
    private val dao: SyncRetryDao,
    private val remoteId: Int,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** A run starts: the last error is out of date. When the sync first failed stays. */
    suspend fun runStarted() = dao.clearNextTry(remoteId)

    /** How long the sync has been failing: since the end of its first failed run, or zero. */
    suspend fun failingFor(): Duration =
        dao.load(remoteId)?.let { (now() - it.firstFailedAt).milliseconds } ?: Duration.ZERO

    /**
     * A run finished at least one file: the sync is making progress, so earlier failures no
     * longer count toward the limit, and its next failure starts a new streak.
     */
    suspend fun progressMade() = dao.delete(remoteId)

    /** A run failed with [error] and will be tried again, no earlier than [nextTryAt]. */
    suspend fun retrying(error: String, nextTryAt: Long) =
        dao.recordRetry(remoteId, now(), NextTry(error, nextTryAt))

    /** The sync ended: it succeeded, failed for good or was cancelled. */
    suspend fun ended() = dao.delete(remoteId)
}
