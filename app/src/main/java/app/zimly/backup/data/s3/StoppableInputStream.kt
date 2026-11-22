package app.zimly.backup.data.s3

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

/**
 * Passes reads through to [source] until [stop] is called. After that, every read throws
 * [InterruptedIOException].
 *
 * When an upload is cancelled, this makes minio-java fail at its next read, so its own error
 * handling aborts the multipart upload. A read that fails on its own (the file was deleted, the
 * storage provider failed) calls [onReadFailure] before the error goes on: with parallel parts,
 * minio aborts at once, and the parts still in flight must be stopped first.
 */
internal class StoppableInputStream(
    source: InputStream,
    private val onReadFailure: () -> Unit = {},
) : FilterInputStream(source) {

    @Volatile
    private var stopped = false

    fun stop() {
        stopped = true
    }

    override fun read(): Int {
        throwIfStopped()
        return reportingFailure { super.read() }
    }

    // FilterInputStream.read(ByteArray) calls this overload too.
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        throwIfStopped()
        return reportingFailure { super.read(buffer, offset, length) }
    }

    private inline fun reportingFailure(read: () -> Int): Int =
        try {
            read()
        } catch (e: IOException) {
            onReadFailure()
            throw e
        }

    private fun throwIfStopped() {
        if (stopped) throw InterruptedIOException("Upload cancelled")
    }
}
