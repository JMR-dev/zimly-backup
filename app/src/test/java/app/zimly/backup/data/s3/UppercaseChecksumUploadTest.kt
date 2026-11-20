package app.zimly.backup.data.s3

import android.util.Log
import io.minio.MinioAsyncClient
import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.random.Random

/**
 * The retry path on its own, against a locked, Garage-like [ObjectLockS3Stub]. It must declare an
 * uppercase `CRC32C`, send a correct CRC32C on every part (the stub verifies it) and handle every
 * object size, including empty objects and a short last part.
 */
class UppercaseChecksumUploadTest {

    private val server = MockWebServer()
    private val stub = ObjectLockS3Stub(objectLockEnabled = true, uppercaseAlgorithmOnly = true)

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>(), any()) } returns 0
        server.dispatcher = stub
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `uploads an empty object`() = runTest { assertUploaded(ByteArray(0)) }

    @Test
    fun `uploads a small object as one part`() = runTest { assertUploaded(randomBytes(1024)) }

    @Test
    fun `uploads an object of exactly one part size`() = runTest { assertUploaded(randomBytes(FIVE_MIB)) }

    @Test
    fun `uploads a short last part`() = runTest { assertUploaded(randomBytes(FIVE_MIB + 1)) }

    private suspend fun assertUploaded(bytes: ByteArray) {
        UppercaseChecksumUpload(client(), BUCKET)
            .upload(ByteArrayInputStream(bytes), NAME, CONTENT_TYPE, bytes.size.toLong())

        assertEquals(listOf("CRC32C"), stub.creates.map { it.checksumAlgorithm })
        val stored = stub.objects.getValue(NAME)
        assertArrayEquals(bytes, stored.bytes)
        assertEquals(CONTENT_TYPE, stored.contentType)
    }

    private fun client(): MinioAsyncClient = MinioAsyncClient.builder()
        .endpoint(server.url("/").toString())
        .region("us-east-1")
        .credentials("key", "secret")
        .build()

    private fun randomBytes(size: Int): ByteArray = Random(size).nextBytes(size)

    private companion object {
        const val FIVE_MIB = 5 * 1024 * 1024
        const val BUCKET = "locked-bucket"
        const val NAME = "folder/object.bin"
        const val CONTENT_TYPE = "image/png"
    }
}
