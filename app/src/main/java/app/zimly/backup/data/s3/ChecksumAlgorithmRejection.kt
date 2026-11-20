package app.zimly.backup.data.s3

import io.minio.errors.ErrorResponseException

/**
 * True when this failure is an S3 server rejecting the *name* of the checksum algorithm. Garage, for
 * example, answers 400 "invalid checksum algorithm" to minio-java's lowercase `crc32c`.
 *
 * Only an HTTP 400 whose message names the checksum algorithm counts. Missing or mismatched
 * checksums (B2's Object Lock error, `BadDigest`, `XAmzContentChecksumMismatch`) are a different
 * problem that a retry wouldn't fix.
 */
fun Throwable.isChecksumAlgorithmRejection(): Boolean {
    val error = s3ErrorInCauseChain() ?: return false
    val message = error.errorResponse().message().orEmpty()
    return error.response().code == HTTP_BAD_REQUEST && ALGORITHM_IN_MESSAGE.containsMatchIn(message)
}

/** minio-java wraps S3 errors, e.g. `CompletionException(MinioException(ErrorResponseException))`. */
private fun Throwable.s3ErrorInCauseChain(): ErrorResponseException? =
    generateSequence(this) { it.cause }
        .filterIsInstance<ErrorResponseException>()
        .firstOrNull()

private const val HTTP_BAD_REQUEST = 400

/**
 * "checksum algorithm" in prose, or the `x-amz-checksum-algorithm` /
 * `x-amz-sdk-checksum-algorithm` header named outright.
 */
private val ALGORITHM_IN_MESSAGE = Regex("checksum[ -]algorithm", RegexOption.IGNORE_CASE)
