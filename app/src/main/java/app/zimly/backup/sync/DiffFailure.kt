package app.zimly.backup.sync

import io.minio.errors.ErrorResponseException
import java.io.IOException

/**
 * The error a failed diff ends the sync with: "[description]: details", with S3's status and
 * error code when S3 answered. A network failure stays an IOException, which SyncWorker retries;
 * anything else becomes a plain Exception.
 */
internal fun diffFailure(description: String, cause: Exception): Exception {
    val message = "$description: ${details(cause)}"
    return if (cause is IOException) IOException(message, cause) else Exception(message, cause)
}

private fun details(cause: Exception): String? =
    if (cause is ErrorResponseException) {
        "status: ${cause.response().code}, message: ${cause.response().message}, " +
            "errorCode: ${cause.errorResponse().code()}"
    } else {
        cause.message
    }
