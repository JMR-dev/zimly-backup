package app.zimly.backup.sync

import androidx.work.Data
import androidx.work.ListenableWorker.Result
import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** How deep to look through an error's causes for a certificate failure. */
private const val MAX_CAUSES = 10

/**
 * How long a sync keeps retrying network errors, from its first failed run. The owner chose a
 * time limit (2026-11-21) over a count: WorkManager's run count also grows when Android stops a
 * run, so a long backup stopped a few times would give up at its next network error.
 */
internal val RETRY_WINDOW: Duration = 24.hours

/**
 * How a sync that ended with [error] finishes, having been failing for [failingFor] (zero
 * before its first failed run). A network error is retried with the work request's backoff
 * until [RETRY_WINDOW] has passed; anything else fails, with its message for the sync screen.
 */
internal fun syncResultOf(error: Throwable, failingFor: Duration): Result = when {
    !isRetryable(error) -> failure(error.message)
    failingFor >= RETRY_WINDOW -> failure("Gave up after ${RETRY_WINDOW.inWholeHours} hours of failed tries: ${error.message}")
    else -> Result.retry()
}

/**
 * A network error a later run may not meet. A TLS failure is an IOException too, but a
 * certificate the phone doesn't trust, or one for another host name, needs the user to act.
 */
private fun isRetryable(error: Throwable): Boolean =
    error is IOException && !isCertificateFailure(error)

private fun isCertificateFailure(error: Throwable): Boolean =
    generateSequence(error) { it.cause }
        .take(MAX_CAUSES)
        .any { it is CertificateException || it is SSLPeerUnverifiedException }

/**
 * Whether this result retries the work. WorkManager restricts its Result.Retry class to its own
 * code, but every retry equals [Result.retry]: Retry's equals compares only the class.
 */
internal fun Result.isRetry(): Boolean = this == Result.retry()

private fun failure(message: String?): Result =
    Result.failure(Data.Builder().putString(SyncOutputs.ERROR, message).build())
