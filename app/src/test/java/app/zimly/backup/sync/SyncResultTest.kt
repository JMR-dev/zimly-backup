package app.zimly.backup.sync

import androidx.work.ListenableWorker.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * How a failed sync ends: a network error is retried a bounded number of times; anything a retry
 * can't fix, such as a certificate the phone doesn't trust, fails at once with its message.
 */
class SyncResultTest {

    @Test
    fun `a network error is retried`() {
        assertTrue(syncResultOf(IOException("connection reset"), runAttemptCount = 0) is Result.Retry)
    }

    @Test
    fun `a network error on the last allowed run fails with its message`() {
        val result = syncResultOf(IOException("connection reset"), runAttemptCount = MAX_SYNC_ATTEMPTS - 1)

        assertEquals("connection reset", errorOf(result))
    }

    /** A listing failure reaches the worker wrapped by diffFailure, as an IOException. */
    @Test
    fun `a certificate the phone doesn't trust fails at once`() {
        val handshake = SSLHandshakeException("handshake failed")
        handshake.initCause(CertificateException("Trust anchor for certification path not found."))
        val error = IOException("Failed to create remote diff: handshake failed", handshake)

        assertEquals("Failed to create remote diff: handshake failed", errorOf(syncResultOf(error, runAttemptCount = 0)))
    }

    @Test
    fun `a certificate for another host name fails at once`() {
        val error = SSLPeerUnverifiedException("Hostname s3.example.com not verified")

        assertEquals("Hostname s3.example.com not verified", errorOf(syncResultOf(error, runAttemptCount = 0)))
    }

    @Test
    fun `any other error fails with its message`() {
        assertEquals("bucket missing", errorOf(syncResultOf(Exception("bucket missing"), runAttemptCount = 0)))
    }

    private fun errorOf(result: Result): String? =
        (result as Result.Failure).outputData.getString(SyncOutputs.ERROR)
}
