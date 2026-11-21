package app.zimly.backup.data.s3

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
     */
    private fun mc(uploadProgressTracker: ProgressTracker? = null): MinioAsyncClient {
        try {
            val client = MinioAsyncClient.builder()
                .httpClient(httpClient.withUploadProgress(uploadProgressTracker))
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
            else newBuilder().addInterceptor(UploadProgressInterceptor(tracker)).build()
    }


    // kotlinx's await() below unwraps the CompletionException minio's futures fail with, so a
    // network failure reaches the sync as the IOException it retries, and a cancelled caller
    // cancels the call.

    override suspend fun bucketExists(): Boolean =
        mc().bucketExists(BucketExistsArgs.builder().bucket(bucket).build()).await()

    override fun listObjects(): List<S3Object> = try {
        mc().listObjects(ListObjectsArgs.builder().bucket(bucket).recursive(true).build())
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
        mc().makeBucket(MakeBucketArgs.builder().bucket(bucket).build()).await()
    }

    override suspend fun stat(name: String): StatObjectResponse =
        mc().statObject(StatObjectArgs.builder().`object`(name).bucket(bucket).build()).await()

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
        val body = response.source().counted(tracker)
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
            val future = mc().getObject(GetObjectArgs.builder().bucket(bucket).`object`(name).build())
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
        mc().removeObject(RemoveObjectArgs.builder().bucket(bucket).`object`(name).build()).await()
    }

    fun removeAll() {

        val delObjs = listObjects().map { DeleteRequest.Object(it.name) }

        val params = RemoveObjectsArgs.builder()
            .bucket(bucket)
            .objects(delObjs)
            .build()

        mc().removeObjects(params).forEach {
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

        val client = mc(uploadProgressTracker = progressTracker)
        try {
            openStream().use { putObject(client, it, name, contentType, size) }
        } catch (e: Exception) {
            if (!e.isChecksumAlgorithmRejection()) throw e
            Log.i(TAG, "Retrying $name with an uppercase checksum algorithm: ${e.message}")
            openStream().use { UppercaseChecksumUpload(client, bucket).upload(it, name, contentType, size) }
        }
        // The upload is done: end observing even if dropped steps left it short of 100%.
        progressTracker.finish()
    }

    /**
     * Uploads through minio-java's own putObject.
     *
     * minio aborts a failed multipart upload in a handler on the future putObject returns, and a
     * cancelled future never runs that handler. So this waits without cancelling the future. If
     * the caller is cancelled, it stops the source, minio fails at its next read and aborts, and
     * this waits for that (up to [CANCEL_SETTLE_TIMEOUT]) before the caller closes the stream.
     * A cancel during the last part can't stop it: that upload completes.
     */
    private suspend fun putObject(
        client: MinioAsyncClient,
        stream: InputStream,
        name: String,
        contentType: String,
        size: Long
    ) {
        val source = StoppableInputStream(stream)
        val args = PutObjectArgs.builder()
            .bucket(bucket)
            .`object`(name)
            .contentType(contentType)
            .stream(source, size, -1)
            .build()
        // asDeferred: awaiting the Deferred can be cancelled without cancelling minio's future.
        val upload = client.putObject(args).asDeferred()
        try {
            upload.await()
        } catch (e: CancellationException) {
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
 * The error a failed listing ends with. minio's listing iterator turns each transport failure
 * into an IllegalStateException that wraps the IOException, or that IOException's own cause
 * when it has one: on Android, a DNS failure's GaiException or a socket error's ErrnoException,
 * neither an IOException. A server's error comes as a MinioException instead. So every one of
 * these becomes an IOException, which the sync retries.
 */
private fun listingFailure(e: IllegalStateException): IOException {
    val cause = e.cause ?: e
    return cause as? IOException ?: IOException("Listing the bucket failed: ${cause.message}", cause)
}

/**
 * Wraps the [Source] in a [ForwardingSource] that emits the bytes read to the passed [ProgressTracker].
 */
fun Source.counted(tracker: ProgressTracker): Source {
    return object : ForwardingSource(this) {
        override fun read(sink: Buffer, byteCount: Long): Long {
            val bytesRead = super.read(sink, byteCount)
            if (bytesRead > 0) {
                tracker.stepBy(bytesRead)
            }
            return bytesRead
        }
    }.buffer()
}
