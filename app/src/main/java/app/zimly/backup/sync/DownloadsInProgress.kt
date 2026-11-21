package app.zimly.backup.sync

import app.zimly.backup.data.db.download.DownloadInProgress
import app.zimly.backup.data.db.download.DownloadInProgressDao

/**
 * The downloads of one remote's sync that started and haven't settled. Opening a download's file
 * erases its old copy, and only the download deletes a cut-off file, so a download is recorded
 * before it starts and forgotten once its file is complete, untouched or deleted. If the app is
 * killed in between, the record stays, and the next sync downloads that key again.
 */
interface DownloadsInProgress {
    /** Blocks, like the rest of the diff it serves, which lists the bucket and folder in blocking calls. */
    fun keys(): Set<String>
    suspend fun started(key: String)
    suspend fun finished(key: String)
}

/** [DownloadsInProgress] in the app's database, for [remoteId]. */
internal class StoredDownloadsInProgress(
    private val dao: DownloadInProgressDao,
    private val remoteId: Int,
) : DownloadsInProgress {

    override fun keys(): Set<String> = dao.keys(remoteId).toSet()

    override suspend fun started(key: String) = dao.insert(DownloadInProgress(remoteId, key))

    override suspend fun finished(key: String) = dao.delete(remoteId, key)
}
