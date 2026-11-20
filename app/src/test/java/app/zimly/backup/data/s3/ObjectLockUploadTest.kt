package app.zimly.backup.data.s3

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.assertFailsWith

/**
 * Uploads through [MinioRepository] against [ObjectLockS3Stub], which rejects PUTs without an
 * integrity header on an Object Lock bucket the way Backblaze B2 does.
 *
 * Runs over plain HTTP and over HTTPS with a self-signed certificate. B2 and AWS are HTTPS-only,
 * and minio-java signs payloads differently there (`UNSIGNED-PAYLOAD` instead of a SHA-256).
 */
@RunWith(Parameterized::class)
class ObjectLockUploadTest(private val scheme: String) {

    private val bucket = "locked-bucket"
    private val server = MockWebServer()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.isLoggable(any(), any()) } returns false
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `locked bucket accepts a small upload`() = runTest {
        assertUploadStored(ObjectLockS3Stub(objectLockEnabled = true), randomBytes(1024))
    }

    @Test
    fun `locked bucket accepts an upload of exactly one part size`() = runTest {
        assertUploadStored(ObjectLockS3Stub(objectLockEnabled = true), randomBytes(FIVE_MIB))
    }

    @Test
    fun `locked bucket accepts an empty upload`() = runTest {
        assertUploadStored(ObjectLockS3Stub(objectLockEnabled = true), ByteArray(0))
    }

    @Test
    fun `locked bucket accepts a two part multipart upload`() = runTest {
        assertUploadStored(ObjectLockS3Stub(objectLockEnabled = true), randomBytes(FIVE_MIB + 1))
    }

    @Test
    fun `locked bucket accepts a three part multipart upload`() = runTest {
        assertUploadStored(ObjectLockS3Stub(objectLockEnabled = true), randomBytes(12 * 1024 * 1024))
    }

    @Test
    fun `unlocked bucket accepts a small upload`() = runTest {
        assertUploadStored(ObjectLockS3Stub(objectLockEnabled = false), randomBytes(1024))
    }

    @Test
    fun `a server that rejects a lowercase checksum algorithm gets an uppercase retry`() = runTest {
        val stub = ObjectLockS3Stub(objectLockEnabled = true, uppercaseAlgorithmOnly = true)

        val streams = assertUploadStored(stub, randomBytes(FIVE_MIB + 1))

        assertEquals(listOf("crc32c", "CRC32C"), stub.creates.map { it.checksumAlgorithm })
        assertEquals("the retry reopens the source", 2, streams.opened.size)
    }

    @Test
    fun `other upload errors are not retried`() = runTest {
        val stub = ObjectLockS3Stub(objectLockEnabled = true, failPart = 1)
        val streams = TrackedStreams(randomBytes(FIVE_MIB + 1))

        assertFailsWith<Exception> { upload(repository(stub), streams) }

        assertEquals(1, stub.creates.size)
        assertEquals(1, streams.opened.size)
        assertTrue("streams closed", streams.allClosed)
    }

    @Test
    fun `a failed part during the retry aborts the multipart upload`() = runTest {
        val stub = ObjectLockS3Stub(objectLockEnabled = true, uppercaseAlgorithmOnly = true, failPart = 2)
        val streams = TrackedStreams(randomBytes(FIVE_MIB + 1))

        assertFailsWith<Exception> { upload(repository(stub), streams) }

        assertEquals(listOf("upload-1"), stub.aborts)
        assertTrue("streams closed", streams.allClosed)
    }

    @Test
    fun `cancelling during the retry aborts the multipart upload`() = runTest {
        val stub = ObjectLockS3Stub(objectLockEnabled = true, uppercaseAlgorithmOnly = true, holdPart = 1)
        val streams = TrackedStreams(randomBytes(FIVE_MIB + 1))
        val repository = repository(stub)

        val upload = launch(Dispatchers.IO) { upload(repository, streams) }
        val held = withContext(Dispatchers.IO) { stub.partHeld.await(30, TimeUnit.SECONDS) }
        assertTrue("part 1 reached the stub", held)
        upload.cancelAndJoin()
        stub.releasePart.countDown()

        assertEquals(listOf("upload-1"), stub.aborts)
        assertTrue("streams closed", streams.allClosed)
    }

    /**
     * Uploads [bytes] and checks the stub stored exactly them, with the given content type, and that
     * progress reached 100% and every opened stream was closed.
     */
    private suspend fun assertUploadStored(stub: ObjectLockS3Stub, bytes: ByteArray): TrackedStreams {
        val streams = TrackedStreams(bytes)

        val lastProgress = upload(repository(stub), streams)

        assertEquals(1f, lastProgress.percentage)
        val stored = stub.objects.getValue(NAME)
        assertArrayEquals(bytes, stored.bytes)
        assertEquals(CONTENT_TYPE, stored.contentType)
        assertTrue("streams closed", streams.allClosed)
        assertTransport()
        return streams
    }

    private suspend fun upload(repository: MinioRepository, streams: TrackedStreams): Progress =
        repository.put(streams::open, NAME, CONTENT_TYPE, streams.size).last()

    /** The first request went over TLS exactly when this run is the HTTPS one. */
    private fun assertTransport() {
        val handshake = server.takeRequest().handshake
        if (scheme == HTTPS) assertNotNull("expected a TLS handshake", handshake) else assertEquals(null, handshake)
    }

    private fun repository(stub: ObjectLockS3Stub): MinioRepository {
        server.dispatcher = stub
        val httpClient = if (scheme == HTTPS) serveSelfSignedHttps() else MinioRepository.client()
        server.start()
        // An explicit region keeps the SDK from asking the stub for the bucket location.
        return MinioRepository(
            server.url("/").toString(), "key", "secret", bucket, "us-east-1", httpClient = httpClient
        )
    }

    /**
     * Makes [server] serve HTTPS with a freshly generated self-signed certificate, and returns a
     * client that trusts exactly that certificate.
     */
    private fun serveSelfSignedHttps(): OkHttpClient {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName(server.hostName)
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        server.useHttps(serverCertificates.sslSocketFactory(), false)

        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        return MinioRepository.client().newBuilder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .build()
    }

    private fun randomBytes(size: Int): ByteArray = Random(size).nextBytes(size)

    /** Opens fresh streams over [bytes] and remembers each, so tests can check they were closed. */
    private class TrackedStreams(private val bytes: ByteArray) {
        val opened = CopyOnWriteArrayList<TrackedStream>()
        val size = bytes.size.toLong()
        val allClosed get() = opened.all { it.closed }

        fun open(): InputStream = TrackedStream(bytes).also { opened += it }
    }

    private class TrackedStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        @Volatile
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }

    companion object {
        private const val FIVE_MIB = 5 * 1024 * 1024
        private const val HTTPS = "https"
        private const val NAME = "folder/object.bin"
        private const val CONTENT_TYPE = "image/png"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun schemes(): List<String> = listOf("http", HTTPS)
    }
}
