package app.zimly.backup.data.s3

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Without a configured region, minio asks the server for the bucket's location and caches the
 * answer in its client. A new client for every call asked again for every file: one more round
 * trip per upload and two per download. One client per repository asks once.
 */
class ClientReuseTest {

    private val server = MockWebServer()
    private val locationLookups = AtomicInteger()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.isLoggable(any(), any()) } returns false
        server.dispatcher = BucketStub()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a repository looks up its bucket's region once`() = runBlocking<Unit> {
        val repository = MinioRepository(server.url("/").toString(), "key", "secret", BUCKET, region = null)

        repository.stat("a.jpg")
        repository.stat("b.jpg")
        repository.stat("c.jpg")

        assertEquals(1, locationLookups.get())
    }

    /** Answers the bucket's location, and any object's metadata. */
    private inner class BucketStub : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.requestUrl ?: return MockResponse().setResponseCode(400)
            return if ("location" in url.queryParameterNames) {
                locationLookups.incrementAndGet()
                MockResponse()
                    .setHeader("Content-Type", "application/xml")
                    .setBody("<LocationConstraint xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">us-east-1</LocationConstraint>")
            } else {
                MockResponse()
                    .setHeader("Content-Type", "image/jpeg")
                    .setHeader("Content-Length", "0")
                    .setHeader("ETag", "\"etag\"")
                    .setHeader("Last-Modified", "Sat, 21 Nov 2026 12:00:00 GMT")
            }
        }
    }

    private companion object {
        const val BUCKET = "bucket"
    }
}
