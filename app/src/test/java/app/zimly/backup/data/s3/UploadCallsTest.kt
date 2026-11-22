package app.zimly.backup.data.s3

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith

/**
 * A cancelled upload stops its part uploads, which makes minio fail and abort the multipart
 * upload. Only part uploads may be stopped: cancelling minio's own AbortMultipartUpload would
 * leave the parts on the server, and cancelling Create or CompleteMultipartUpload would leave
 * the outcome to timing.
 */
class UploadCallsTest {

    private val server = MockWebServer()
    private val heldRequests = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val requests = AtomicInteger()
    private val uploadCalls = UploadCalls { true }
    private val client = OkHttpClient.Builder().addInterceptor(uploadCalls).build()

    init {
        server.dispatcher = HoldingDispatcher()
    }

    @After
    fun tearDown() {
        release.countDown()
        server.shutdown()
    }

    @Test
    fun `a part upload in flight is cancelled`() {
        val part = send(partUpload())
        awaitHeld()

        uploadCalls.cancelInFlight()

        val failure = assertFailsWith<ExecutionException> { part.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        assertTrue("${failure.cause}", failure.cause is IOException)
    }

    @Test
    fun `an abort in flight is not cancelled`() {
        val abort = send(Request.Builder().url(server.url("/bucket/key?uploadId=u1")).delete().build())
        awaitHeld()

        uploadCalls.cancelInFlight()
        release.countDown()

        assertEquals(200, abort.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).code)
    }

    @Test
    fun `a part upload started after a cancel fails without reaching the server`() {
        uploadCalls.cancelInFlight()

        assertFailsWith<IOException> { client.newCall(partUpload()).execute() }
        assertEquals(0, requests.get())
    }

    @Test
    fun `the next upload's parts go ahead after a reset`() {
        uploadCalls.cancelInFlight()
        uploadCalls.reset()
        release.countDown()

        client.newCall(partUpload()).execute().use { assertTrue(it.isSuccessful) }
    }

    private fun partUpload() = Request.Builder()
        .url(server.url("/bucket/key?partNumber=1&uploadId=u1"))
        .put("part".toRequestBody("application/octet-stream".toMediaType()))
        .build()

    /** Sends [request] asynchronously and completes with its response, or its failure. */
    private fun send(request: Request): CompletableFuture<Response> {
        val result = CompletableFuture<Response>()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                result.completeExceptionally(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { result.complete(it) }
            }
        })
        return result
    }

    private fun awaitHeld() {
        assertTrue("the request reached the server", heldRequests.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    }

    /** Holds every request until [release], so it is in flight when the test cancels. */
    private inner class HoldingDispatcher : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            requests.incrementAndGet()
            heldRequests.countDown()
            release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            return MockResponse().setResponseCode(200)
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
    }
}
