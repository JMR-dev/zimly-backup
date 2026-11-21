package app.zimly.backup.sync

import java.io.OutputStream

/**
 * Passes writes on to [out], counting the bytes, and records whether [close] succeeded. A
 * download uses it to tell a complete local file from a cut-off one.
 */
internal class CountingOutputStream(private val out: OutputStream) : OutputStream() {

    @Volatile
    var count = 0L
        private set

    @Volatile
    var isClosed = false
        private set

    override fun write(b: Int) {
        out.write(b)
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        count += len
    }

    override fun flush() = out.flush()

    /** Closes [out]; [isClosed] turns true only if that succeeded, so every byte was handed over. */
    override fun close() {
        out.close()
        isClosed = true
    }
}
