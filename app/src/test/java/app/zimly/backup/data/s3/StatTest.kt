package app.zimly.backup.data.s3

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * minio's async calls fail with a CompletionException that wraps the cause. The sync retries only
 * an IOException, so the repository must hand over the cause itself, or a network blip on stat()
 * (called before every download) ends the whole sync.
 */
class StatTest {

    private val server = MockWebServer()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.isLoggable(any(), any()) } returns false
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a connection dropped during stat ends with an IO error`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertFailsWith<IOException> {
            withTimeout(10.seconds) { repository().stat("photo.jpg") }
        }
    }

    @Test
    fun `a connection dropped during remove ends with an IO error`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertFailsWith<IOException> {
            withTimeout(10.seconds) { repository().remove("photo.jpg") }
        }
    }

    /**
     * Listing the bucket is the first network call of every sync. minio's listing iterator turns a
     * network error into an IllegalStateException that wraps it.
     */
    @Test
    fun `a connection dropped during listing ends with an IO error`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertFailsWith<IOException> { repository().listObjects() }
    }

    /**
     * minio wraps the IOException's own cause when it has one. On Android a DNS failure's cause is
     * a GaiException, a RuntimeException, and a socket error's an ErrnoException: neither is an
     * IOException, and the sync retries only an IOException.
     */
    @Test
    fun `a listing failure whose cause isn't an IO error still ends with an IO error`() {
        val cause = RuntimeException("android_getaddrinfo failed: EAI_NODATA")
        val failingDns = Dns { host -> throw UnknownHostException(host).apply { initCause(cause) } }
        val client = OkHttpClient.Builder().dns(failingDns).build()
        val repository = MinioRepository("http://s3.invalid:9000", "key", "secret", "bucket", "us-east-1", httpClient = client)

        val failure = assertFailsWith<IOException> { repository.listObjects() }

        assertSame(cause, failure.cause)
    }

    // An explicit region keeps the SDK from asking the stub for the bucket's location.
    private fun repository() =
        MinioRepository(server.url("/").toString(), "key", "secret", "bucket", "us-east-1")
}
