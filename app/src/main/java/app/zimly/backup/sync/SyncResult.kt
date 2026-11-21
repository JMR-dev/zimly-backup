package app.zimly.backup.sync

import androidx.work.Data
import androidx.work.ListenableWorker.Result
import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLPeerUnverifiedException

/** How deep to look through an error's causes for a certificate failure. */
private const val MAX_CAUSES = 10

/**
 * How a sync that ended with [error] finishes. A network error is retried with the work
 * request's backoff; anything else fails, with its message for the sync screen.
 *
 * WorkManager's run count can't bound the retries: it grows on every run start, runs Android
 * stopped included, so a long backup stopped a few times would fail at its next network error.
 * Bounding them needs a count of failed runs of our own, which the visible-retries work adds.
 */
internal fun syncResultOf(error: Throwable): Result =
    if (isRetryable(error)) Result.retry() else failure(error)

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
