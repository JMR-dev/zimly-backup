package app.zimly.backup.data.s3

import io.minio.ObjectWriteArgs.MAX_MULTIPART_COUNT
import io.minio.ObjectWriteArgs.MIN_MULTIPART_SIZE

/**
 * The part size minio-java's own `putObject` picks for an object of [objectSize] bytes.
 *
 * The smallest part that fits the object in at most 10,000 parts, rounded up to a multiple of 5 MiB.
 * So any non-empty object up to ~48 GiB gets 5 MiB parts, even one smaller than that; callers that
 * buffer a part should cap the buffer at the object size. An empty object gets 0.
 */
fun partSize(objectSize: Long): Long {
    val smallestFittingPart = ceilDiv(objectSize, MAX_MULTIPART_COUNT.toLong())
    return ceilDiv(smallestFittingPart, MIN_MULTIPART_SIZE.toLong()) * MIN_MULTIPART_SIZE
}

private fun ceilDiv(dividend: Long, divisor: Long): Long = (dividend + divisor - 1) / divisor
