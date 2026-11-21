package app.zimly.backup.sync

import android.net.Uri
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
    private val inProgress: DownloadsInProgress,
    private val samplePeriod: Long = 500L
) : SyncService {

    companion object {
        val TAG: String? = DownloadSyncService::class.simpleName

        /**
         * Determines which remote [S3Object]s should be downloaded based on local presence and modification time.
         *
         * A remote object is selected if:
         * * It does not exist locally (by matching its [localPathOf] to [ContentObject.relPath]), or
         * * Its [S3Object.modified] is more recent than the corresponding local [ContentObject.lastModified], or
         * * Its download started and never settled ([inProgress]): the app was killed, and the
         *   local file may be cut off, with a newer timestamp than the remote's.
         *
         * Keys with no local path of their own are never selected: folder markers (ending in '/')
         * and keys with empty, "." or ".." segments.
         *
         * @param remotes The list of remote [S3Object]s available.
         * @param locales The list of local [ContentObject]s currently stored.
         * @param inProgress The keys whose downloads started and never settled.
         * @return A list of remote [S3Object]s that need to be downloaded.
         */
        fun calculateDownloads(
            remotes: List<S3Object>,
            locales: List<ContentObject>,
            inProgress: Set<String> = emptySet(),
        ): List<S3Object> {

            val localsByPath = locales.associateBy { it.relPath }

            return remotes.filter { remote ->
                val localPath = localPathOf(remote.name) ?: return@filter false
                val local = localsByPath[localPath]
                val isNewOrUpdatedRemote = local == null || local.lastModified < remote.modified.toInstant().toEpochMilli()
                isNewOrUpdatedRemote || remote.name in inProgress
            }
        }

        /**
         * Local files left by the folder-marker bug fixed in 4c306ed: a marker such as "Camera/" was
         * downloaded as an empty file named "Camera", which keeps the real folder from being created
         * (the provider makes "Camera (1)" instead, and every sync another). Only an empty file whose
         * marker is still in the bucket counts; a file with data in it is never touched.
         */
        internal fun strayMarkerFiles(remotes: List<S3Object>, locales: List<ContentObject>): List<ContentObject> {
            val markerFolders = remotes.map { it.name }
                .filter { it.endsWith("/") }
                .mapNotNull { localPathOf(it.removeSuffix("/")) }
                .toSet()
            return locales.filter { it.size == 0L && it.relPath in markerFolders }
        }

        /** Keys skipped because they name no local file, apart from folder markers, which are expected. */
        private fun unmappableKeys(remotes: List<S3Object>): List<String> =
            remotes.map { it.name }.filter { !it.endsWith("/") && localPathOf(it) == null }
    }

    override fun calculateDiff(): DownloadDiff {
        try {
            val remotes = s3Repository.listObjects()
            val objects = localContentResolver.listObjects()

            val downloads = calculateDownloads(remotes, objects, inProgress.keys())
            unmappableKeys(remotes).forEach { Log.w(TAG, "Skipping '$it': the key names no local file of its own") }
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
        withContext(Dispatchers.IO) { removeStrayMarkerFiles(diff) }
        diff.diff.asFlow()
            // Another request, for the size to download.
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
        val key = s3Obj.`object`()
        // calculateDownloads selects only keys that have a local path.
        val path = checkNotNull(localPathOf(key)) { "No local path for $key" }
        val target = LocalTarget(path, s3Obj.size())
        inProgress.started(key)
        return s3Repository.get(key, s3Obj.size(), target::open)
            .onCompletion { failure ->
                // NonCancellable: a cancel is one of the failures, and must not skip settling.
                withContext(NonCancellable + Dispatchers.IO) { settle(key, target, failure) }
            }
    }

    /**
     * Ends one download's record. A cut-off file is deleted first; if that fails, the record
     * stays, so the next sync downloads the file again.
     */
    private suspend fun settle(key: String, target: LocalTarget, failure: Throwable?) {
        val settled = when (failure) {
            null -> true
            else -> target.partialDocument()?.let { deletePartial(it, failure) } ?: true
        }
        if (settled) inProgress.finished(key)
    }

    /** Deletes the empty files from the old folder-marker bug, so their folders can be created. */
    private fun removeStrayMarkerFiles(diff: DownloadDiff) {
        strayMarkerFiles(diff.remotes, diff.locals).forEach {
            Log.i(TAG, "Removing '${it.relPath}', an empty file where the folder of a marker belongs")
            localContentResolver.delete(it.uri)
        }
    }

    /**
     * The local file of one download: opens it on request, and remembers which document that was
     * and what reached it. A file that fails to open was never written to; the resolver removes it
     * if it had just created it.
     */
    private inner class LocalTarget(private val path: String, private val size: Long) {
        @Volatile
        private var document: Uri? = null

        @Volatile
        private var stream: CountingOutputStream? = null

        fun open(): OutputStream {
            val opened = localContentResolver.openForWriting(path)
            document = opened.uri
            return CountingOutputStream(opened.stream).also { stream = it }
        }

        /**
         * The document, if it was opened but not every byte reached it and closed with it. A
         * failure after that, such as a cancel while the last progress is on its way, leaves a
         * complete file, so this is null then.
         */
        fun partialDocument(): Uri? {
            val opened = stream ?: return null
            return document.takeUnless { opened.isClosed && opened.count == size }
        }
    }

    /**
     * A download that failed or was cancelled partway leaves a cut-off file (opening it erased the
     * old version). Its timestamp is newer than the remote's, so later syncs would skip it for
     * good. Deleting it, by the document this download opened, lets the next sync download it
     * again. A failure to delete is attached to [failure], which still ends the sync.
     *
     * @return whether the file is gone
     */
    private fun deletePartial(document: Uri, failure: Throwable): Boolean =
        try {
            localContentResolver.delete(document)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete the partial download $document", e)
            failure.addSuppressed(e)
            false
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