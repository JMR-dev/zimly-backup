package app.zimly.backup.data.s3

import io.minio.errors.ErrorResponseException
import io.minio.messages.ErrorResponse
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/** The sync and the bucket check describe a failure to the user in the same words. */
class S3ErrorMessageTest {

    @Test
    fun `an S3 error shows its status, S3's own message and its error code`() {
        val error = s3Error(status = 403, code = "AccessDenied", message = "Access Denied.")

        assertEquals("status: 403, message: Access Denied., errorCode: AccessDenied", s3ErrorMessage(error))
    }

    @Test
    fun `any other failure shows its own message`() {
        assertEquals("connection reset", s3ErrorMessage(IOException("connection reset")))
    }

    /** The HTTP reason phrase ("Forbidden") says less than S3's message, so it isn't used. */
    private fun s3Error(status: Int, code: String, message: String): ErrorResponseException {
        val request = Request.Builder().url("https://s3.example.com/bucket").build()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("Forbidden")
            .build()
        val error = ErrorResponse(code, message, "bucket", null, "/bucket", "request-id", "host-id")
        return ErrorResponseException(error, response, "")
    }
}
