package app.zimly.backup.data.db.retry

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import app.zimly.backup.data.db.remote.Remote

/**
 * A remote's sync that has failed with a network error and is being retried: when it first
 * failed, and [nextTry] while it waits for its next run. Deleted with its remote.
 */
@Entity(
    tableName = "sync_retry",
    foreignKeys = [
        ForeignKey(
            entity = Remote::class,
            parentColumns = ["uid"],
            childColumns = ["remote_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SyncRetry(
    @PrimaryKey @ColumnInfo(name = "remote_id") val remoteId: Int,
    /** Epoch milliseconds of the end of the first failed run. */
    @ColumnInfo(name = "first_failed_at") val firstFailedAt: Long,
    /** Null while a run is in progress: its error is then out of date. */
    @Embedded val nextTry: NextTry?,
)

/** What a waiting sync shows: the error that ended its last run, and the earliest next try. */
data class NextTry(
    @ColumnInfo(name = "last_error") val lastError: String,
    /** Epoch milliseconds; the earliest start, which Android may hold back. */
    @ColumnInfo(name = "next_try_at") val at: Long,
)
