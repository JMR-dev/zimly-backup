package app.zimly.backup.data.db.download

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import app.zimly.backup.data.db.remote.Remote

/**
 * A download of [key] into [remoteId]'s folder that started and hasn't settled (see
 * app.zimly.backup.sync.DownloadsInProgress). Deleted with its remote.
 */
@Entity(
    tableName = "download_in_progress",
    primaryKeys = ["remote_id", "object_key"],
    foreignKeys = [
        ForeignKey(
            entity = Remote::class,
            parentColumns = ["uid"],
            childColumns = ["remote_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DownloadInProgress(
    @ColumnInfo(name = "remote_id") val remoteId: Int,
    /** The S3 key being downloaded. */
    @ColumnInfo(name = "object_key") val key: String,
)
