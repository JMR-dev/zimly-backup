package app.zimly.backup.sync

import android.content.Context
import androidx.core.net.toUri
import app.zimly.backup.data.db.ZimlyDatabase
import app.zimly.backup.data.db.remote.Remote
import app.zimly.backup.data.db.remote.SyncDirection
import app.zimly.backup.data.media.ContentType
import app.zimly.backup.data.media.LocalDocumentsResolver
import app.zimly.backup.data.media.LocalMediaResolverImpl
import app.zimly.backup.data.s3.MinioRepository
import kotlinx.coroutines.flow.Flow

interface SyncService {
    fun calculateDiff(): Diff
    fun synchronize(): Flow<SyncProgress>

    companion object {
        /**
         * Provides the [SyncService] based on [Remote] configuration and profile.
         */
        fun get(context: Context, remote: Remote): SyncService {

            val s3Repository =
                MinioRepository(remote.url, remote.key, remote.secret, remote.bucket, remote.region, remote.virtualHostedStyle)

            return when (remote.contentType) {
                ContentType.MEDIA -> {
                    val contentResolver = LocalMediaResolverImpl(context, remote.contentUri)
                    UploadSyncService(s3Repository, contentResolver)
                }

                ContentType.FOLDER -> {
                    val contentResolver = LocalDocumentsResolver(context, remote.contentUri.toUri())
                    when (remote.direction) {
                        SyncDirection.UPLOAD -> UploadSyncService(s3Repository, contentResolver)
                        SyncDirection.DOWNLOAD -> DownloadSyncService(
                            s3Repository,
                            contentResolver,
                            downloadsInProgress(context, remote),
                        )
                    }
                }
            }
        }
    }
}

/** The record of [remote]'s unsettled downloads, in the app's database. */
private fun downloadsInProgress(context: Context, remote: Remote): DownloadsInProgress {
    val remoteId = checkNotNull(remote.uid) { "A saved remote has an id" }
    val dao = ZimlyDatabase.getInstance(context.applicationContext).downloadInProgressDao()
    return StoredDownloadsInProgress(dao, remoteId)
}

abstract class Diff {
    abstract var totalObjects: Int
    abstract var totalBytes: Long
}
