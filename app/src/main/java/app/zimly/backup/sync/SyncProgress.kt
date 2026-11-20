package app.zimly.backup.sync

data class SyncProgress(
    val transferredBytes: Long,
    val transferredFiles: Int,
    val percentage: Float,
    val bytesPerSecond: Long?,
    val totalFiles: Int,
    val totalBytes: Long
) {
    companion object {
        val EMPTY = SyncProgress(0, 0, 0F, null, 0, 0)

        /**
         * Share of [totalBytes] already transferred, from 0 to 1. A sync with no bytes to
         * transfer (for example only empty files) counts as complete, instead of dividing by zero.
         */
        fun percentageOf(transferredBytes: Long, totalBytes: Long): Float =
            if (totalBytes == 0L) 1f else transferredBytes.toFloat() / totalBytes
    }
}
