package app.zimly.backup.data.s3

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import kotlin.random.Random

/**
 * Object Lock uploads on a real Android runtime, over HTTPS with a self-signed certificate.
 *
 * The JVM unit tests can't cover this: on Android, minio-java parses S3's XML with the
 * Woodstox/StAX libraries the app bundles, uses okhttp-android instead of okhttp-jvm, and gets
 * Android's TLS stack.
 *
 * [ObjectLockS3Stub] verifies CRC32C with `java.util.zip.CRC32C`, which exists from API 34.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 34)
class ObjectLockUploadDeviceTest {

    private val server = MockWebServer()

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun lockedBucketAcceptsASmallUpload() {
        assertUploadStored(ObjectLockS3Stub(objectLockEnabled = true), Random(1).nextBytes(1024))
    }

    @Test
    fun lockedBucketAcceptsAMultipartUpload() {
        assertUploadStored(ObjectLockS3Stub(objectLockEnabled = true), Random(12).nextBytes(12 * MIB))
    }

    @Test
    fun aServerThatRejectsALowercaseAlgorithmGetsAnUppercaseRetry() {
        val stub = ObjectLockS3Stub(objectLockEnabled = true, uppercaseAlgorithmOnly = true)

        assertUploadStored(stub, Random(5).nextBytes(5 * MIB + 1))

        assertEquals(listOf("crc32c", "CRC32C"), stub.creates.map { it.checksumAlgorithm })
    }

    private fun assertUploadStored(stub: ObjectLockS3Stub, bytes: ByteArray) = runBlocking {
        val repository = repository(stub)

        val lastProgress = repository
            .put({ ByteArrayInputStream(bytes) }, NAME, CONTENT_TYPE, bytes.size.toLong())
            .last()

        assertEquals(1f, lastProgress.percentage)
        val stored = stub.objects.getValue(NAME)
        assertArrayEquals(bytes, stored.bytes)
        assertEquals(CONTENT_TYPE, stored.contentType)
    }

    private fun repository(stub: ObjectLockS3Stub): MinioRepository {
        server.dispatcher = stub
        val httpClient = server.serveSelfSignedHttps()
        server.start()
        return MinioRepository(
            server.url("/").toString(), "key", "secret", "locked-bucket", "us-east-1", httpClient = httpClient
        )
    }

    private companion object {
        const val MIB = 1024 * 1024
        const val NAME = "folder/object.bin"
        const val CONTENT_TYPE = "image/png"
    }
}
