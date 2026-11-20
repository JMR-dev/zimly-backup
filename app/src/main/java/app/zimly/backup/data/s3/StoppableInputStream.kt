package app.zimly.backup.data.s3

import java.io.FilterInputStream
import java.io.InputStream
import java.io.InterruptedIOException

/**
 * Passes reads through to [source] until [stop] is called. After that, every read throws
 * [InterruptedIOException].
 *
 * When an upload is cancelled, this makes minio-java fail at its next read, so its own error
 * handling aborts the multipart upload.
 */
internal class StoppableInputStream(source: InputStream) : FilterInputStream(source) {

    @Volatile
    private var stopped = false

    fun stop() {
        stopped = true
    }

    override fun read(): Int {
        throwIfStopped()
        return super.read()
    }

    // FilterInputStream.read(ByteArray) calls this overload too.
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        throwIfStopped()
        return super.read(buffer, offset, length)
    }

    private fun throwIfStopped() {
        if (stopped) throw InterruptedIOException("Upload cancelled")
    }
}
