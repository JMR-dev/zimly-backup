package app.zimly.backup.data.s3

import io.minio.errors.ErrorResponseException

/**
 * A failure in words for the user: when S3 answered, its HTTP status, S3's own message and its
 * error code; otherwise the failure's own message. The sync and the bucket check both use it.
 */
internal fun s3ErrorMessage(failure: Exception): String? =
    if (failure is ErrorResponseException) {
        "status: ${failure.response().code}, message: ${failure.errorResponse().message()}, " +
            "errorCode: ${failure.errorResponse().code()}"
    } else {
        failure.message
    }
