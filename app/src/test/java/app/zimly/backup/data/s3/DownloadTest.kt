package app.zimly.backup.data.s3

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * [MinioRepository.get] against a stub server. A download must end its flow: with 100% progress
 * when every byte arrived, or with the error when it failed. It opens the output only once the
 * server answers with the object (opening erases the local file), and closes it either way. A flow
 * that never ends hangs the sync; one that ends normally after a failure would count a truncated
 * file as downloaded.
 */
class DownloadTest {

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
    fun `a download stores every byte and reports 100 percent`() = runBlocking {
        val bytes = Random(1).nextBytes(SIZE)
        server.enqueue(objectResponse(bytes))
        val output = TrackedOutput()

        val last = withTimeout(TIMEOUT) { repository().get(NAME, bytes.size.toLong(), output::open).last() }

        assertEquals(1f, last.percentage)
        assertArrayEquals(bytes, output.toByteArray())
        assertTrue("output closed", output.closed)
    }

    @Test
    fun `a download cut off partway ends with an IO error`() = runBlocking {
        val bytes = Random(2).nextBytes(SIZE)
        server.enqueue(objectResponse(bytes).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val output = TrackedOutput()

        assertFailsWith<IOException> {
            withTimeout(TIMEOUT) { repository().get(NAME, bytes.size.toLong(), output::open).toList() }
        }
        assertTrue("output closed", output.closed)
    }

    @Test
    fun `an empty object downloads`() = runBlocking {
        server.enqueue(objectResponse(ByteArray(0)))
        val output = TrackedOutput()

        val last = withTimeout(TIMEOUT) { repository().get(NAME, 0, output::open).last() }

        assertEquals(1f, last.percentage)
        assertTrue("output closed", output.closed)
    }

    /** The object shrank between the sync reading its size and downloading it. */
    @Test
    fun `a download that gets fewer bytes than announced still ends`() = runBlocking {
        val bytes = Random(3).nextBytes(SIZE / 2)
        server.enqueue(objectResponse(bytes))
        val output = TrackedOutput()

        withTimeout(TIMEOUT) { repository().get(NAME, SIZE.toLong(), output::open).toList() }

        assertArrayEquals(bytes, output.toByteArray())
        assertTrue("output closed", output.closed)
    }

    /** minio wraps it in a CompletionException; the sync retries only an IOException. */
    @Test
    fun `a connection dropped before the response ends with an IO error`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val output = TrackedOutput()

        assertFailsWith<IOException> {
            withTimeout(TIMEOUT) { repository().get(NAME, SIZE.toLong(), output::open).toList() }
        }
        assertFalse("output never opened", output.opened)
    }

    /** Without a check between reads, a cancelled download read on to the end of its body. */
    @Test
    fun `a cancelled download stops reading`() = runBlocking {
        val bytes = Random(4).nextBytes(SLOW_SIZE)
        server.enqueue(objectResponse(bytes).throttleBody(SLOW_CHUNK, SLOW_PERIOD_MS, TimeUnit.MILLISECONDS))
        val output = TrackedOutput()
        val bytesArrived = CompletableDeferred<Unit>()

        // Cancel once bytes are flowing: the tracker's first value (0 bytes) comes before the
        // request is even answered, and a cancel then never reaches the read loop.
        val download = launch(Dispatchers.IO) {
            repository().get(NAME, bytes.size.toLong(), output::open).collect {
                if (it.totalReadBytes > 0) bytesArrived.complete(Unit)
            }
        }
        withTimeout(TIMEOUT) { bytesArrived.await() }
        withTimeout(TIMEOUT) { download.cancelAndJoin() }

        assertTrue("stopped early, after ${output.size()} of ${bytes.size} bytes", output.size() < bytes.size)
        assertTrue("output closed", output.closed)
    }

    /** Opening the output erases the local file, so a refused request must leave it alone. */
    @Test
    fun `a refused request ends with its error and never opens the output`() = runBlocking {
        server.enqueue(noSuchKey())
        val output = TrackedOutput()

        assertFailsWith<Exception> {
            withTimeout(TIMEOUT) { repository().get(NAME, SIZE.toLong(), output::open).toList() }
        }
        assertFalse("output never opened", output.opened)
    }

    // An explicit region keeps the SDK from asking the stub for the bucket's location.
    private fun repository() =
        MinioRepository(server.url("/").toString(), "key", "secret", BUCKET, "us-east-1")

    private fun objectResponse(bytes: ByteArray) = MockResponse()
        .setHeader("Content-Type", "application/octet-stream")
        .setHeader("ETag", "\"etag\"")
        .setBody(Buffer().write(bytes))

    private fun noSuchKey() = MockResponse()
        .setResponseCode(404)
        .setHeader("Content-Type", "application/xml")
        .setBody(
            "<Error><Code>NoSuchKey</Code><Message>The specified key does not exist.</Message>" +
                "<Key>$NAME</Key><BucketName>$BUCKET</BucketName></Error>"
        )

    /** The local file a download writes: remembers whether it was opened and closed. */
    private class TrackedOutput : ByteArrayOutputStream() {
        @Volatile
        var opened = false

        @Volatile
        var closed = false

        fun open(): OutputStream {
            opened = true
            return this
        }

        override fun close() {
            closed = true
            super.close()
        }
    }

    private companion object {
        const val BUCKET = "bucket"
        const val NAME = "photo.jpg"
        const val SIZE = 256 * 1024

        // 2 MiB at 32 KiB per 100 ms takes about 6.4 seconds to arrive.
        const val SLOW_SIZE = 2 * 1024 * 1024
        const val SLOW_CHUNK = 32L * 1024
        const val SLOW_PERIOD_MS = 100L
        val TIMEOUT = 10.seconds
    }
}
