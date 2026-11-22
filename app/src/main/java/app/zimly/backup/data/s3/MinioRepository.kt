package app.zimly.backup.data.s3

import android.system.ErrnoException
import android.util.Log
import io.minio.BucketExistsArgs
import io.minio.GetObjectArgs
import io.minio.GetObjectResponse
import io.minio.ListObjectsArgs
import io.minio.MakeBucketArgs
import io.minio.MinioAsyncClient
import io.minio.PutObjectArgs
import io.minio.RemoveObjectArgs
import io.minio.RemoveObjectsArgs
import io.minio.StatObjectArgs
import io.minio.StatObjectResponse
import io.minio.messages.DeleteRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.future.asDeferred
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okio.Buffer
import okio.ForwardingSource
import okio.Source
import okio.buffer
import okio.sink
import okio.source
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.ZonedDateTime
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds


data class S3Object(
    /**
     * S3 object name is the path.
     */
    var name: String,
    var size: Long,
    var checksum: String,
    var modified: ZonedDateTime,
)

class MinioRepository(
    private val url: String,
    private val key: String,
    private val secret: String,
    private val bucket: String,
    private val region: String? = null,
    private val virtualHostedStyle: Boolean = false,
    /**
     * Base HTTP client. Each upload derives its own progress-tracking client from it, so they share
     * one connection pool. Tests pass a client that trusts their self-signed certificate.
     */
    private val httpClient: OkHttpClient = client(),
) : S3Repository {

    /**
     * [virtualHostedStyle] is a huge mess in the underlying SDK, see
     * [io.minio.BaseS3Client] and [io.minio.MinioAsyncClient.Builder.endpoint].
     *
     * Gist is (I think):
     *   * AWS and aliyuncs.com endpoints are automagically set to use virtualHostedStyle
     *   * Tencent and other providers need to set this explicitly if needed, but it's not exposed
     *     through the builder.
     *   * The bucket name needs to be stripped of the URL, and the region configured explicitly:
     *     It automagically prefixes the bucket in the URL. If a vendor does not prefix the bucket name, but
     *     relies on virtual host style, things won't work.
     *   * Minio Java SDK is .. bad :(
     *
     * One client serves the repository's whole life: minio caches the bucket's region in it, and
     * a new client for every call asked the server for that region again, for every file.
     */
    private val mc: MinioAsyncClient by lazy { buildClient() }

    /**
     * The progress tracker of the upload in flight, which the client's upload interceptor reports
     * to. A sync uploads one file at a time; [put] refuses a second concurrent upload rather than
     * mix their progress.
     */
    private val uploadInFlight = AtomicReference<ProgressTracker?>(null)

    /** The upload's HTTP calls, which a cancel stops (see [putObject]). */
    private val uploadCalls = UploadCalls { uploadInFlight.get() != null }

    /**
     * Builds the repository's minio client (see [mc]).
     */
    private fun buildClient(): MinioAsyncClient {
        try {
            val uploadClient = httpClient.newBuilder()
                .addInterceptor(uploadCalls)
                .addInterceptor(UploadProgressInterceptor(uploadInFlight::get))
                .build()
            val client = MinioAsyncClient.builder()
                .httpClient(uploadClient)
                .endpoint(url)
                .region(region) // This might override some AWS stuff happening inside #endpoint()
                .credentials(key, secret)
                .build()

            if (virtualHostedStyle) {
                client.enableVirtualStyleEndpoint()
            }
            return client
        } catch (e: Exception) {
            throw Exception("Failed to initialize S3 client: ${e.message}", e)
        }
    }

    companion object {

        private val TAG: String? = MinioRepository::class.simpleName

        /** How long a cancelled upload waits for minio to abort, see [putObject]. */
        private val CANCEL_SETTLE_TIMEOUT = 30.seconds

        /** How much a download reads between checks for a cancel. */
        private const val COPY_CHUNK_BYTES = 64L * 1024

        /** How many parts of a multipart upload go up at once, see [putObject]. */
        private const val PARALLEL_PARTS = 2

        /**
         * Creates a http client with optional [UploadProgressInterceptor].
         *
         * Partly taken from [io.minio.Http.newDefaultClient]
         */
        fun client(uploadProgressTracker: ProgressTracker? = null): OkHttpClient {
            val timeout = TimeUnit.MINUTES.toMillis(5)

            return OkHttpClient()
                .newBuilder()
                .connectTimeout(timeout, TimeUnit.MILLISECONDS)
                .writeTimeout(timeout, TimeUnit.MILLISECONDS)
                .readTimeout(timeout, TimeUnit.MILLISECONDS)
                .protocols(listOf(Protocol.HTTP_1_1))
                .build()
                .withUploadProgress(uploadProgressTracker)
        }

        /**
         * This client plus an [UploadProgressInterceptor] reporting to [tracker], or the client
         * itself when there's no tracker.
         *
         * TODO networkInterceptor? Alternatively attach a builder#eventListenerFactory and listen to
         * requestBodyEnd, but same problems with request body returning too early. Might still be
         * slicker than the interceptor.
         */
        private fun OkHttpClient.withUploadProgress(tracker: ProgressTracker?): OkHttpClient =
            if (tracker == null) this
            else newBuilder().addInterceptor(UploadProgressInterceptor { tracker }).build()
    }


    // kotlinx's await() below unwraps the CompletionException minio's futures fail with, so a
    // network failure reaches the sync as the IOException it retries, and a cancelled caller
    // cancels the call.

    override suspend fun bucketExists(): Boolean =
        mc.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()).await()

    override fun listObjects(): List<S3Object> = try {
        mc.listObjects(ListObjectsArgs.builder().bucket(bucket).recursive(true).build())
            .map { it.get() }
            .map {
                val name = it.objectName()
                val size = it.size()
                val checksum = it.etag()
                val modified = it.lastModified()
                S3Object(name, size, checksum, modified)
            }
    } catch (e: IllegalStateException) {
        throw listingFailure(e)
    }

    override suspend fun createBucket(bucket: String) {
        mc.makeBucket(MakeBucketArgs.builder().bucket(bucket).build()).await()
    }

    override suspend fun stat(name: String): StatObjectResponse =
        mc.statObject(StatObjectArgs.builder().`object`(name).bucket(bucket).build()).await()

    override suspend fun get(name: String): GetObjectResponse = getObject(name)

    /**
     * Downloads a file from S3 and tracks the [Progress].
     *
     * Important: It tracks the progress when writing to the [OutputStream] instead of intercepting
     * it on network traffic. Otherwise the actual bytes read and expected are off, possibly due
     * to gzip or similar.
     * It's also important to note that in this case, network latency is already accounted for, which
     * is not the case for [put], hence we have to use the okio interceptor there.
     *
     * [openOutput] is called once the server answers with the object, and the stream it returns
     * is closed here.
     */
    override suspend fun get(name: String, size: Long, openOutput: () -> OutputStream): Flow<Progress> =
        channelFlow {
            val progressTracker = ProgressTracker(size)

            // Launch progress observer
            val progressJob = launch {
                progressTracker.observe().collect { send(it) }
            }

            // The output opens only once the server answers with the object: opening it may erase
            // the local file, which a refused request must leave alone. Opening it makes blocking
            // storage-provider calls, so it runs on an IO thread with the copy. use { } closes the
            // response and the output on success and on any failure, a cancel's included. A
            // failure ends the flow with that error, which also cancels the progress observer.
            getObject(name).use { response ->
                withContext(Dispatchers.IO) {
                    openOutput().use { output -> copyBody(response, output, size, progressTracker) }
                }
            }

            // The body is done: end observing, even short of the size given (the object shrank
            // after it was stat'ed), then let the last progress through.
            progressTracker.finish()
            progressJob.join()
        }

    /**
     * Copies the response body to [output], counting each read. It checks for cancellation between
     * reads, so a cancelled download stops at its next read instead of reading on to the end; a
     * read blocked on a stalled connection still waits for the HTTP client's read timeout.
     *
     * It fails with an IOException once more than [size] bytes arrive (the object grew after its
     * size was read), so the loop ends after at most [size] + 1 bytes.
     */
    private suspend fun copyBody(response: GetObjectResponse, output: OutputStream, size: Long, tracker: ProgressTracker) {
        val body = response.source().counted(tracker::stepBy)
        output.sink().buffer().use { sink ->
            var copied = 0L
            while (true) { // Ends at the end of the body, past [size], or at a cancel.
                currentCoroutineContext().ensureActive()
                val read = body.read(sink.buffer, COPY_CHUNK_BYTES)
                if (read == -1L) break
                copied += read
                if (copied > size) throw IOException("${response.`object`()} is longer than its $size bytes: it changed during the download")
                sink.emitCompleteSegments()
            }
            sink.flush()
        }
    }

    /**
     * Waits for minio's getObject call like kotlinx's await(): the failure's cause is unwrapped
     * from minio's CompletionException. A response handed over after the caller is cancelled is
     * closed. Cancelling only cancels minio's last stage, not the HTTP call under it: if that call
     * answers after the cancel, minio never builds a response to close, and the connection is left
     * to OkHttp's leak detection.
     */
    private suspend fun getObject(name: String): GetObjectResponse =
        suspendCancellableCoroutine { continuation ->
            val future = mc.getObject(GetObjectArgs.builder().bucket(bucket).`object`(name).build())
            future.whenComplete { response, failure ->
                if (failure != null) {
                    continuation.resumeWithException((failure as? CompletionException)?.cause ?: failure)
                } else {
                    continuation.resume(response) { _, dropped, _ -> dropped.close() }
                }
            }
            continuation.invokeOnCancellation { future.cancel(true) }
        }

    override suspend fun remove(name: String) {
        mc.removeObject(RemoveObjectArgs.builder().bucket(bucket).`object`(name).build()).await()
    }

    fun removeAll() {

        val delObjs = listObjects().map { DeleteRequest.Object(it.name) }

        val params = RemoveObjectsArgs.builder()
            .bucket(bucket)
            .objects(delObjs)
            .build()

        mc.removeObjects(params).forEach {
            val err = it.get()
            err?.let { Log.e(TAG, "Error deleting ${err.objectName()}: ${err.message()}") }
        }
    }

    /**
     * Uploads the object and reports [Progress]. [openStream] opens a fresh stream over the content
     * each time it's called, because a retry has to read it again.
     *
     * minio-java names its checksum algorithm in lowercase (`crc32c`), which some servers, such as
     * Garage, reject. Only then is the upload retried once, through [UppercaseChecksumUpload].
     * Progress stays correct: it counts only successful PUT bodies, and the rejection comes at
     * CreateMultipartUpload, before any part is sent.
     */
    override suspend fun put(
        openStream: () -> InputStream,
        name: String,
        contentType: String,
        size: Long
    ): Flow<Progress> = channelFlow {

        val progressTracker = ProgressTracker(size)
        launch {
            progressTracker.observe().collect { send(it) }
        }

        check(uploadInFlight.compareAndSet(null, progressTracker)) { "Another upload is already running on $bucket" }
        try {
            try {
                openStream().use { putObject(mc, it, name, contentType, size) }
            } catch (e: Exception) {
                if (!e.isChecksumAlgorithmRejection()) throw e
                Log.i(TAG, "Retrying $name with an uppercase checksum algorithm: ${e.message}")
                openStream().use { UppercaseChecksumUpload(mc, bucket).upload(it, name, contentType, size) }
            }
        } finally {
            uploadInFlight.set(null)
            uploadCalls.reset()
        }
        // The upload is done: end observing even if dropped steps left it short of 100%.
        progressTracker.finish()
    }

    /**
     * Uploads through minio-java's own putObject.
     *
     * minio aborts a failed multipart upload in a handler on the future putObject returns, and a
     * cancelled future never runs that handler. So this waits without cancelling the future. If
     * the caller is cancelled, it cancels the part uploads in flight, and any that start later
     * (minio reads parts ahead, so the source alone may never be read again), and stops the source;
     * minio fails and aborts, and this waits for that (up to [CANCEL_SETTLE_TIMEOUT]) before the
     * caller closes the stream. Only part uploads are cancelled (see [UploadCalls]), so minio's
     * abort, and a CompleteMultipartUpload already on its way, go ahead. A source that fails on
     * its own cancels the part uploads the same way before minio aborts.
     */
    private suspend fun putObject(
        client: MinioAsyncClient,
        stream: InputStream,
        name: String,
        contentType: String,
        size: Long
    ) {
        val source = StoppableInputStream(stream) { uploadCalls.cancelInFlight() }
        val args = PutObjectArgs.builder()
            .bucket(bucket)
            .`object`(name)
            .contentType(contentType)
            .stream(source, size, -1)
            // Two parts at once overlap one part's server commit and round trip with the next
            // part's transfer. minio holds up to 2 × parts + 1 part buffers (2 uploading, 2
            // queued, 1 being read), each about 8 MiB for a 5 MiB part: about 40 MiB, so not more.
            .parallelUploads(PARALLEL_PARTS)
            .build()
        // asDeferred: awaiting the Deferred can be cancelled without cancelling minio's future.
        val upload = client.putObject(args).asDeferred()
        try {
            upload.await()
        } catch (e: CancellationException) {
            uploadCalls.cancelInFlight()
            source.stop()
            waitForMinioToSettle(upload)
            throw e
        }
    }

    /**
     * Gives minio time to fail and abort after a cancel. If it takes longer (a stalled network
     * waits out the client's timeouts), minio still aborts once it fails; this just stops waiting.
     */
    private suspend fun waitForMinioToSettle(upload: Deferred<*>) {
        withContext(NonCancellable) {
            withTimeoutOrNull(CANCEL_SETTLE_TIMEOUT) { upload.join() }
        }
    }
}

interface S3Repository {
    fun listObjects(): List<S3Object>
    suspend fun put(
        openStream: () -> InputStream,
        name: String,
        contentType: String,
        size: Long
    ): Flow<Progress>

    suspend fun createBucket(bucket: String)
    suspend fun bucketExists(): Boolean
    suspend fun get(name: String): GetObjectResponse
    suspend fun stat(name: String): StatObjectResponse
    suspend fun get(name: String, size: Long, openOutput: () -> OutputStream): Flow<Progress>
    suspend fun remove(name: String)
}

/**
 * The error a failed listing ends with. minio's listing iterator wraps any failure that isn't
 * its own exception in an IllegalStateException: for a transport failure, the IOException, or
 * that IOException's own cause when it has one. On Android that cause is a DNS failure's
 * GaiException or a socket error's ErrnoException, neither an IOException. Those become an
 * IOException, which the sync retries; any other cause, such as a bug, is handed over as it is
 * and fails the sync with its message.
 */
private fun listingFailure(e: IllegalStateException): Exception = when (val cause = e.cause) {
    is IOException -> cause
    null -> e
    else -> if (isAndroidNetworkError(cause)) IOException("Listing the bucket failed: ${cause.message}", cause) else e
}

/**
 * GaiException is hidden from the SDK, so it is matched by name; it hasn't moved since Android 5,
 * and the app needs Android 10.
 */
private fun isAndroidNetworkError(cause: Throwable): Boolean =
    cause is ErrnoException || cause.javaClass.name == "android.system.GaiException"

/** How many read bytes go into one progress step: reporting every ~8 KiB read cost real CPU. */
internal const val PROGRESS_STEP_BYTES = 256L * 1024

/**
 * Wraps the [Source] in a [ForwardingSource] that reports the bytes read to [step], once at least
 * [PROGRESS_STEP_BYTES] have gathered, and the rest at the end of the source.
 */
internal fun Source.counted(step: (Long) -> Unit): Source = object : ForwardingSource(this) {
    private var unreported = 0L

    override fun read(sink: Buffer, byteCount: Long): Long {
        val bytesRead = super.read(sink, byteCount)
        if (bytesRead > 0) unreported += bytesRead
        if (unreported >= PROGRESS_STEP_BYTES || (bytesRead == -1L && unreported > 0)) {
            step(unreported)
            unreported = 0
        }
        return bytesRead
    }
}
