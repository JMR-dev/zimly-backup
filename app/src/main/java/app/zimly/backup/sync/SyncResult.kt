package app.zimly.backup.sync

import androidx.work.Data
import androidx.work.ListenableWorker.Result
import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * The most runs a sync gets when each one ends in a network error. With the work request's
 * exponential backoff from 1 minute, ten runs span about eight and a half hours. Time offline
 * doesn't count: the work waits for a connection before each run.
 */
internal const val MAX_SYNC_ATTEMPTS = 10

/** How deep to look through an error's causes for a certificate failure. */
private const val MAX_CAUSES = 10

/**
 * How a sync that ended with [error] finishes, on the run that WorkManager numbers
 * [runAttemptCount] (0 for the first). A network error is retried until the last allowed run;
 * anything else fails, with its message for the sync screen.
 */
internal fun syncResultOf(error: Throwable, runAttemptCount: Int): Result =
    if (isRetryable(error) && runAttemptCount < MAX_SYNC_ATTEMPTS - 1) Result.retry() else failure(error)

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

private fun failure(error: Throwable): Result =
    Result.failure(Data.Builder().putString(SyncOutputs.ERROR, error.message).build())
