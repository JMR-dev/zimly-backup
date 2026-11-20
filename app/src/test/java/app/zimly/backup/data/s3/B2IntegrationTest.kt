package app.zimly.backup.data.s3

import android.util.Log
import app.zimly.backup.readTestResource
import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

/**
 * Uploads to a real Backblaze B2 bucket that has Object Lock with a default retention. That is the
 * case from the original bug report: "Content-MD5 OR x-amz-checksum- HTTP header is required for Put
 * Object requests with Object Lock parameters".
 *
 * Use a dedicated test bucket with a short default retention, such as 1 day. Uploaded objects can't
 * be deleted until it expires, so this test doesn't clean up after itself.
 *
 * Needs `S3_B2_TEST_ENDPOINT` (e.g. `https://s3.us-west-000.backblazeb2.com`), `S3_B2_TEST_REGION`
 * (e.g. `us-west-000`), `S3_B2_TEST_BUCKET`, `S3_B2_TEST_KEY` and `S3_B2_TEST_SECRET`.
 *
 * App log lines are printed, so the output shows whether B2 rejected minio-java's lowercase
 * checksum algorithm and the uppercase retry ran.
 */
class B2IntegrationTest {

    private val runPrefix = "zimly-object-lock-test/${Instant.now().epochSecond}"

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } answers { println("Log.i: ${secondArg<String>()}"); 0 }
        every { Log.w(any(), any<String>(), any()) } answers { println("Log.w: ${secondArg<String>()}"); 0 }
        every { Log.e(any(), any()) } answers { println("Log.e: ${secondArg<String>()}"); 0 }
        every { Log.isLoggable(any(), any()) } returns false
    }

    @Test
    fun `single part upload to a locked B2 bucket`() = runTest(timeout = 5.minutes) {
        assertUploaded("$runPrefix/small.png", readTestResource("/testdata/test_image2.png"))
    }

    @Test
    fun `multipart upload to a locked B2 bucket`() = runTest(timeout = 5.minutes) {
        assertUploaded("$runPrefix/multipart.png", readTestResource("/testdata/test_image.png"))
    }

    private suspend fun assertUploaded(name: String, bytes: ByteArray) {
        val repository = repository()

        val lastProgress = repository
            .put({ ByteArrayInputStream(bytes) }, name, CONTENT_TYPE, bytes.size.toLong())
            .last()

        assertEquals(1f, lastProgress.percentage)
        val stat = repository.stat(name)
        assertEquals(CONTENT_TYPE, stat.contentType())
        assertArrayEquals(bytes, repository.get(name).use { it.readBytes() })
        // B2 reports the lock only to keys with the readFileRetentions capability, so print it.
        println("$name: ${bytes.size} bytes uploaded, lock mode ${stat.lockMode()}")
    }

    private fun repository() = MinioRepository(
        env("S3_B2_TEST_ENDPOINT"),
        env("S3_B2_TEST_KEY"),
        env("S3_B2_TEST_SECRET"),
        env("S3_B2_TEST_BUCKET"),
        env("S3_B2_TEST_REGION"),
    )

    private fun env(name: String): String =
        requireNotNull(System.getenv(name)) { "B2IntegrationTest needs the $name environment variable" }

    private companion object {
        const val CONTENT_TYPE = "image/png"
    }
}
