package app.zimly.backup.data.s3

import io.minio.errors.ErrorResponseException
import io.minio.errors.MinioException
import io.minio.messages.ErrorResponse
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CompletionException

class ChecksumAlgorithmRejectionTest {

    @Test
    fun `Garage's invalid checksum algorithm is a rejection`() {
        assertTrue(s3Error(400, "InvalidRequest", "Bad request: invalid checksum algorithm").isChecksumAlgorithmRejection())
    }

    @Test
    fun `a message naming the algorithm header is a rejection`() {
        assertTrue(s3Error(400, "InvalidArgument", "Invalid x-amz-checksum-algorithm value").isChecksumAlgorithmRejection())
        assertTrue(s3Error(400, "InvalidRequest", "Bad X-Amz-Sdk-Checksum-Algorithm").isChecksumAlgorithmRejection())
    }

    @Test
    fun `a rejection wrapped by the SDK is still found`() {
        val wrapped = CompletionException(MinioException(s3Error(400, "InvalidRequest", "invalid checksum algorithm")))
        assertTrue(wrapped.isChecksumAlgorithmRejection())
    }

    @Test
    fun `B2's Object Lock error is not a rejection`() {
        assertFalse(s3Error(400, "InvalidRequest", ObjectLockS3Stub.OBJECT_LOCK_MESSAGE).isChecksumAlgorithmRejection())
    }

    @Test
    fun `checksum mismatches are not rejections`() {
        assertFalse(
            s3Error(400, "XAmzContentChecksumMismatch", "The provided 'x-amz-checksum' header does not match what was computed.")
                .isChecksumAlgorithmRejection()
        )
        assertFalse(
            s3Error(400, "BadDigest", "The Content-MD5 you specified did not match what we received.")
                .isChecksumAlgorithmRejection()
        )
    }

    @Test
    fun `only a 400 counts`() {
        assertFalse(s3Error(403, "AccessDenied", "invalid checksum algorithm").isChecksumAlgorithmRejection())
    }

    @Test
    fun `errors without an S3 response are not rejections`() {
        assertFalse(IOException("invalid checksum algorithm").isChecksumAlgorithmRejection())
    }

    private fun s3Error(status: Int, code: String, message: String): ErrorResponseException {
        val request = Request.Builder().url("https://s3.example.com/bucket/key").build()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("stub")
            .build()
        val error = ErrorResponse(code, message, "bucket", "key", "/bucket/key", "request-id", "host-id")
        return ErrorResponseException(error, response, "")
    }
}
