package app.zimly.backup.data.s3

import android.util.Log
import app.zimly.backup.SILO_IMAGE
import app.zimly.backup.readTestResource
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.minio.SetObjectLockConfigurationArgs
import io.minio.messages.ObjectLockConfiguration
import io.minio.messages.RetentionMode
import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.testcontainers.containers.MinIOContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import java.io.ByteArrayInputStream
import kotlin.random.Random

/**
 * Uploads to an Object Lock bucket (GOVERNANCE, 1 day default retention) on Silo, a MinIO fork,
 * served over HTTPS with a self-signed certificate made for this run.
 *
 * Silo doesn't *require* a checksum on locked buckets the way B2 does (see [ObjectLockUploadTest]
 * for that). It does verify every checksum it gets, so a wrong CRC32C fails these uploads with
 * `XAmzContentChecksumMismatch`. Every object must also come back locked.
 */
class SiloIntegrationTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.isLoggable(any(), any()) } returns false
    }

    @Test
    fun `small upload to a locked bucket is stored and locked`() = runTest {
        // test_image2.png is 0.6 MiB, below the 5 MiB part size, so this is one PutObject.
        val image = readTestResource("/testdata/test_image2.png")
        assertUploadedAndLocked("small.png", "image/png", image)
    }

    @Test
    fun `multipart upload to a locked bucket is stored and locked`() = runTest {
        val bytes = Random(12).nextBytes(12 * 1024 * 1024)
        assertUploadedAndLocked("multipart.bin", "application/octet-stream", bytes)
    }

    private suspend fun assertUploadedAndLocked(name: String, contentType: String, bytes: ByteArray) {
        val repository = MinioRepository(endpoint(), USER, PASSWORD, BUCKET, httpClient = trustingClient)

        val lastProgress = repository
            .put({ ByteArrayInputStream(bytes) }, name, contentType, bytes.size.toLong())
            .last()

        assertEquals(1f, lastProgress.percentage)
        val stat = repository.stat(name)
        assertEquals(RetentionMode.GOVERNANCE, stat.lockMode())
        assertEquals(contentType, stat.contentType())
        assertArrayEquals(bytes, repository.get(name).use { it.readBytes() })
    }

    companion object {
        private const val USER = "test"
        private const val PASSWORD = "testtest"
        private const val BUCKET = "locked-bucket"
        private const val S3_PORT = 9000
        private const val CERTS_DIR = "/certs"

        /** Self-signed, valid for the addresses Testcontainers exposes the container on. */
        private val certificate = selfSignedCertificate("localhost", "127.0.0.1")

        private val trustingClient = clientTrusting(certificate)

        private val silo: MinIOContainer =
            MinIOContainer(SILO_IMAGE)
                .withUserName(USER)
                .withPassword(PASSWORD)
                .withCopyToContainer(Transferable.of(certificate.certificatePem()), "$CERTS_DIR/public.crt")
                .withCopyToContainer(Transferable.of(certificate.privateKeyPkcs8Pem()), "$CERTS_DIR/private.key")
                .withCommand("server", "--certs-dir", CERTS_DIR, "--console-address", ":9001", "/data")
                .waitingFor(
                    Wait.forHttps("/minio/health/live")
                        .forPort(S3_PORT)
                        .allowInsecure()
                )

        private fun endpoint() = "https://${silo.host}:${silo.getMappedPort(S3_PORT)}"

        @JvmStatic
        @BeforeClass
        fun startSilo() {
            silo.start()
            createLockedBucket()
        }

        @JvmStatic
        @AfterClass
        fun stopSilo() {
            silo.stop()
        }

        /** An Object Lock bucket whose default retention locks every new object. */
        private fun createLockedBucket() {
            val admin = MinioClient.builder()
                .httpClient(trustingClient)
                .endpoint(endpoint())
                .credentials(USER, PASSWORD)
                .build()
            admin.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).objectLock(true).build())
            val defaultRetention = ObjectLockConfiguration(
                RetentionMode.GOVERNANCE,
                ObjectLockConfiguration.RetentionDurationDays(1)
            )
            admin.setObjectLockConfiguration(
                SetObjectLockConfigurationArgs.builder().bucket(BUCKET).config(defaultRetention).build()
            )
        }
    }
}
