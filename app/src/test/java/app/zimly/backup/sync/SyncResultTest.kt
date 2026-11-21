package app.zimly.backup.sync

import android.util.Log
import androidx.work.ListenableWorker.Result
import app.zimly.backup.data.media.WriteableContentResolver
import app.zimly.backup.data.s3.MinioRepository
import app.zimly.backup.data.s3.clientTrusting
import app.zimly.backup.data.s3.selfSignedCertificate
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import kotlin.time.Duration.Companion.seconds

/**
 * How a failed sync ends: a network error is retried, but a TLS failure a retry can't fix, such
 * as a certificate the phone doesn't trust, fails at once so the sync screen shows it. The
 * certificate cases run a real handshake against a stub server, through the real listing and
 * diff, because the exception chain they produce is what decides the result.
 */
class SyncResultTest {

    private val server = MockWebServer()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.isLoggable(any(), any()) } returns false
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a network error is retried`() {
        assertTrue(syncResultOf(IOException("connection reset")) is Result.Retry)
    }

    @Test
    fun `any other error fails`() {
        assertTrue(syncResultOf(Exception("bucket missing")) is Result.Failure)
    }

    @Test
    fun `a certificate the phone doesn't trust fails at once`() {
        serveHttps(selfSignedCertificate("127.0.0.1"))

        val error = syncError(MinioRepository.client())

        assertTrue("$error", syncResultOf(error) is Result.Failure)
    }

    @Test
    fun `a trusted certificate for another host name fails at once`() {
        val otherHost = selfSignedCertificate("s3.example.com")
        serveHttps(otherHost)

        val error = syncError(clientTrusting(otherHost))

        assertTrue("$error", syncResultOf(error) is Result.Failure)
    }

    private fun serveHttps(certificate: HeldCertificate) {
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start()
    }

    /**
     * The error a download sync against the stub ends with (listing the bucket fails first),
     * checked to come from TLS so that no other failure passes for it.
     */
    private fun syncError(client: OkHttpClient): Throwable = runBlocking {
        // 127.0.0.1, not "localhost": OkHttp also tries localhost's IPv6 address, where nothing
        // listens, and minio then reports that refused connection instead of the TLS failure.
        // An explicit region keeps the SDK from asking the stub for the bucket's location.
        val url = "https://127.0.0.1:${server.port}/"
        val repository = MinioRepository(url, "key", "secret", "bucket", "us-east-1", httpClient = client)
        val local: WriteableContentResolver = mockk(relaxed = true)
        val error = try {
            withTimeout(10.seconds) { DownloadSyncService(repository, local).synchronize().toList() }
            null
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("The sync neither failed nor finished", e)
        } catch (e: Exception) {
            e
        }
        checkNotNull(error) { "The sync succeeded against an untrusted server" }
        val causes = generateSequence<Throwable>(error) { it.cause }.take(MAX_CAUSES).toList()
        assertTrue("not a TLS failure: ${error.stackTraceToString()}", causes.any { it is SSLException || it is CertificateException })
        error
    }

    private companion object {
        const val MAX_CAUSES = 10
    }
}
