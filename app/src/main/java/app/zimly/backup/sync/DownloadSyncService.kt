package app.zimly.backup.sync

import android.util.Log
import app.zimly.backup.data.media.ContentObject
import app.zimly.backup.data.media.WriteableContentResolver
import app.zimly.backup.data.s3.Progress
import app.zimly.backup.data.s3.S3Object
import app.zimly.backup.data.s3.S3Repository
import io.minio.StatObjectResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.withContext
import java.io.OutputStream

class DownloadSyncService(
    private val s3Repository: S3Repository,
    private val localContentResolver: WriteableContentResolver,
    private val samplePeriod: Long = 500L
) : SyncService {

    companion object {
        val TAG: String? = DownloadSyncService::class.simpleName

        /**
         * Determines which remote [S3Object]s should be downloaded based on local presence and modification time.
         *
         * A remote object is selected if:
         * * It does not exist locally (by matching [S3Object.name] to [ContentObject.relPath]), or
         * * Its [S3Object.modified] is more recent than the corresponding local [ContentObject.lastModified].
         *
         * @param remotes The list of remote [S3Object]s available.
         * @param locales The list of local [ContentObject]s currently stored.
         * @return A list of remote [S3Object]s that need to be downloaded.
         */
        fun calculateDownloads(
            remotes: List<S3Object>,
            locales: List<ContentObject>
        ): List<S3Object> {

            val localsByPath = locales.associateBy { it.relPath }

            return remotes.filter { remote ->
                // A key ending in '/' is an empty "folder" marker, not a file to download.
                if (remote.name.endsWith("/")) return@filter false
                val local = localsByPath[remote.name]
                val isNewOrUpdatedRemote = local == null || local.lastModified < remote.modified.toInstant().toEpochMilli()
                isNewOrUpdatedRemote
            }
        }
    }

    override fun calculateDiff(): DownloadDiff {
        try {
            val remotes = s3Repository.listObjects()
            val objects = localContentResolver.listObjects()

            val downloads = calculateDownloads(remotes, objects)
            val size = downloads.sumOf { it.size }

            return DownloadDiff(downloads.size, size, remotes, objects, downloads)
        } catch (e: Exception) {
            val failure = diffFailure("Failed to create remote diff", e)
            Log.e(TAG, failure.message.orEmpty(), e)
            throw failure
        }
    }

    override fun synchronize(): Flow<SyncProgress> = flow {

        val diff = calculateDiff()
        diff.diff.asFlow()
            // TODO: Really another request for the content-type?
            .map { s3Obj -> s3Repository.stat(s3Obj.name) }
            .map { s3StatObj -> download(s3StatObj) }
            .toSyncProgress(diff.totalObjects, diff.totalBytes)
            .sampleWithLast(samplePeriod)
            .collect { emit(it) }
    }

    /**
     * One file's download. Its local file opens only once the server answers with the object, and
     * is deleted only if this download opened it and then failed or was cancelled before the whole
     * file was written.
     */
    private suspend fun download(s3Obj: StatObjectResponse): Flow<Progress> {
        val target = LocalTarget(s3Obj.`object`(), s3Obj.contentType(), s3Obj.size())
        return s3Repository.get(s3Obj.`object`(), s3Obj.size(), target::open)
            .onCompletion { failure ->
                // NonCancellable: a cancel is one of the failures, and must not skip the delete.
                if (failure != null && target.isPartial()) {
                    withContext(NonCancellable + Dispatchers.IO) { deletePartial(target.path, failure) }
                }
            }
    }

    /**
     * The local file of one download: opens it on request, and remembers what reached it. A file
     * that fails to open was never written to; the resolver removes it if it had just created it.
     */
    private inner class LocalTarget(val path: String, private val mimeType: String, private val size: Long) {
        @Volatile
        private var stream: CountingOutputStream? = null

        fun open(): OutputStream =
            CountingOutputStream(localContentResolver.createOutputStream(path, mimeType)).also { stream = it }

        /**
         * Opened, but not every byte reached the file and closed with it. A failure after that,
         * such as a cancel while the last progress is on its way, leaves a complete file.
         */
        fun isPartial(): Boolean {
            val opened = stream ?: return false
            return !(opened.isClosed && opened.count == size)
        }
    }

    /**
     * A download that failed or was cancelled partway leaves a cut-off file (opening it erased the
     * old version). Its timestamp is newer than the remote's, so later syncs would skip it for
     * good. Deleting it lets the next sync download it again. A failure to delete is attached to
     * [failure], which still ends the sync.
     */
    private fun deletePartial(objectPath: String, failure: Throwable) {
        try {
            localContentResolver.delete(objectPath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete the partial download $objectPath", e)
            failure.addSuppressed(e)
        }
    }

}

data class DownloadDiff(

    override var totalObjects: Int = 0,
    override var totalBytes: Long = 0,

    val remotes: List<S3Object>,
    val locals: List<ContentObject>,
    val diff: List<S3Object>,
) : Diff()

/**
 * Extension function that uses [Flow.sample] but ensures the last value is emitted.
 */
@OptIn(FlowPreview::class)
fun <T> Flow<T>.sampleWithLast(periodMillis: Long): Flow<T> = flow {
    require(periodMillis > 0) { "Sample period should be positive" }
    var last: T? = null
    var emitted: T? = null

    this@sampleWithLast
        .onEach { last = it }
        .sample(periodMillis)
        .collect {
            emitted = it
            emit(it)
        }

    if (last != null && last != emitted) {
        emit(last!!)
    }
}