package app.zimly.backup.data.s3

/**
 * Share of [totalBytes] already transferred, from 0 to 1. A transfer with no bytes (for example
 * only empty files) counts as complete, instead of dividing by zero.
 */
fun percentageOf(transferredBytes: Long, totalBytes: Long): Float =
    if (totalBytes == 0L) 1f else transferredBytes.toFloat() / totalBytes
