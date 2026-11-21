package app.zimly.backup.data.s3

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
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

    // An explicit region keeps the SDK from asking the stub for the bucket's location.
    private fun repository() =
        MinioRepository(server.url("/").toString(), "key", "secret", "bucket", "us-east-1")
}
