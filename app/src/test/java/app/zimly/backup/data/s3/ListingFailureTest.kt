package app.zimly.backup.data.s3

import android.system.ErrnoException
import android.system.OsConstants
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.UnknownHostException
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

/**
 * minio's listing iterator wraps a failure in an IllegalStateException, and for a network error
 * it wraps the IOException's cause, not the IOException. On Android that cause is a GaiException
 * (DNS) or an ErrnoException (sockets). Those must reach the sync as an IOException, which it
 * retries; anything else, such as a listing it can't parse, must fail with its message.
 * Robolectric supplies Android's real exception classes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ListingFailureTest {

    private val server = MockWebServer()

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a DNS failure ends with an IO error`() {
        val cause = gaiException()

        val failure = assertFailsWith<IOException> { repositoryFailingDnsWith(cause).listObjects() }

        assertSame(cause, failure.cause)
    }

    @Test
    fun `a socket error ends with an IO error`() {
        val cause = ErrnoException("connect", OsConstants.ENETUNREACH)

        val failure = assertFailsWith<IOException> { repositoryFailingDnsWith(cause).listObjects() }

        assertSame(cause, failure.cause)
    }

    @Test
    fun `a listing that can't be parsed is not a network error`() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/xml").setBody("<ListBucketResult><Contents>"))

        val failure = assertFails { repository(server.url("/").toString()).listObjects() }

        assertFalse("retried as a network error: $failure", failure is IOException)
    }

    /**
     * minio wraps any cause that isn't its own exception the same way, a bug's included. Only a
     * network cause makes the listing failure an IOException; anything else fails with its message.
     */
    @Test
    fun `a wrapped cause that isn't a network error is not one`() {
        val cause = IllegalArgumentException("not a network error")

        val failure = assertFails { repositoryFailingDnsWith(cause).listObjects() }

        assertFalse("retried as a network error: $failure", failure is IOException)
    }

    /**
     * Android's DNS failure cause. Its class is hidden from the SDK the tests compile against, so
     * it is built by reflection from Robolectric's copy of Android.
     */
    private fun gaiException(): Exception =
        Class.forName("android.system.GaiException")
            .getConstructor(String::class.java, Int::class.javaPrimitiveType)
            .newInstance("android_getaddrinfo", OsConstants.EAI_NODATA) as Exception

    /** A repository whose DNS lookups fail the way Android's do, with [cause] under the IOException. */
    private fun repositoryFailingDnsWith(cause: Exception): MinioRepository {
        val dns = Dns { host -> throw UnknownHostException(host).apply { initCause(cause) } }
        return repository("http://s3.invalid:9000", OkHttpClient.Builder().dns(dns).build())
    }

    // An explicit region keeps the SDK from asking the server for the bucket's location.
    private fun repository(url: String, client: OkHttpClient = MinioRepository.client()) =
        MinioRepository(url, "key", "secret", "bucket", "us-east-1", httpClient = client)
}
