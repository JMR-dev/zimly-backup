package app.zimly.backup.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.OutputStream
import kotlin.test.assertFailsWith

/**
 * A download is complete only if every byte reached its file and the file closed without an
 * error. The download closes the stream twice (okio's sink, then the stream itself), so a close
 * that failed the first time must not count as a success the second time.
 */
class CountingOutputStreamTest {

    @Test
    fun `counts the bytes written through it`() {
        val stream = CountingOutputStream(OutputStream.nullOutputStream())

        stream.write(ByteArray(10), 2, 5)
        stream.write(1)

        assertEquals(6L, stream.count)
    }

    @Test
    fun `a close that succeeds marks it closed`() {
        val stream = CountingOutputStream(OutputStream.nullOutputStream())

        stream.close()

        assertTrue(stream.isClosed)
    }

    @Test
    fun `a close that failed stays failed when closed again`() {
        val stream = CountingOutputStream(FailsFirstClose())

        assertFailsWith<IOException> { stream.close() }
        stream.close()

        assertFalse(stream.isClosed)
    }

    /** Like a file whose close fails once (a full disk on flush), then is already closed. */
    private class FailsFirstClose : OutputStream() {
        private var closed = false

        override fun write(b: Int) = Unit

        override fun close() {
            if (closed) return
            closed = true
            throw IOException("No space left on device")
        }
    }
}
