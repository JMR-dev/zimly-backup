package app.zimly.backup.data.s3

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.ByteArrayInputStream
import kotlin.random.Random

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

    /**
     * Uploads [bytes] and checks the stub stored exactly them, with the given content type, and that
     * progress reached 100%.
     */
    private suspend fun assertUploadStored(stub: ObjectLockS3Stub, bytes: ByteArray) {
        val name = "folder/object.bin"
        val contentType = "image/png"

        val lastProgress = repository(stub)
            .put(ByteArrayInputStream(bytes), name, contentType, bytes.size.toLong())
            .last()

        assertEquals(1f, lastProgress.percentage)
        val stored = stub.objects.getValue(name)
        assertArrayEquals(bytes, stored.bytes)
        assertEquals(contentType, stored.contentType)
        assertTransport()
    }

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

    companion object {
        private const val FIVE_MIB = 5 * 1024 * 1024
        private const val HTTPS = "https"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun schemes(): List<String> = listOf("http", HTTPS)
    }
}
