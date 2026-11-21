package app.zimly.backup.data.s3

import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException

/**
 * minio's listing iterator turns each transport failure into an IllegalStateException that wraps
 * the IOException, or that IOException's cause when it has one. On Android a DNS failure's cause
 * is a GaiException and a socket error's an ErrnoException, neither an IOException. The sync
 * retries only an IOException, so every one of these must come out as one.
 */
class ListingFailureTest {

    @Test
    fun `a wrapped IOException is handed over as it is`() {
        val cause = IOException("unexpected end of stream")

        assertSame(cause, listingFailure(IllegalStateException(cause)))
    }

    @Test
    fun `a wrapped cause that isn't an IOException becomes the cause of one`() {
        val cause = RuntimeException("android_getaddrinfo failed: EAI_NODATA")

        val failure = listingFailure(IllegalStateException(cause))

        assertSame(cause, failure.cause)
    }

    @Test
    fun `an IllegalStateException without a cause becomes the cause of an IOException`() {
        val error = IllegalStateException("listing failed")

        val failure = listingFailure(error)

        assertSame(error, failure.cause)
    }
}
