package app.zimly.backup.data.s3

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * OkHttp writes a request body again when it retries after a recoverable connection failure.
 * The body counts what one attempt sent, so a retry mustn't add to the failed attempt's count.
 */
class ProgressRequestBodyTest {

    @Test
    fun `a body written twice counts one attempt`() {
        val body = ProgressRequestBody(ByteArray(1000).toRequestBody("application/octet-stream".toMediaType()))

        body.writeTo(Buffer())
        body.writeTo(Buffer())

        assertEquals(1000L, body.read)
    }
}
