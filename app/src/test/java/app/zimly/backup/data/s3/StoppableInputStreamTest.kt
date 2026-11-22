package app.zimly.backup.data.s3

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import kotlin.test.assertFailsWith

class StoppableInputStreamTest {

    @Test
    fun `reads pass through until stopped`() {
        val stream = StoppableInputStream(ByteArrayInputStream(byteArrayOf(1, 2, 3)))

        assertEquals(1, stream.read())
        val rest = ByteArray(2)
        assertEquals(2, stream.read(rest))
        assertArrayEquals(byteArrayOf(2, 3), rest)
    }

    @Test
    fun `every read fails once stopped`() {
        val stream = StoppableInputStream(ByteArrayInputStream(byteArrayOf(1, 2, 3)))

        stream.stop()

        assertFailsWith<InterruptedIOException> { stream.read() }
        assertFailsWith<InterruptedIOException> { stream.read(ByteArray(2)) }
        assertFailsWith<InterruptedIOException> { stream.read(ByteArray(2), 0, 2) }
    }

    /**
     * With parts uploading in parallel, minio aborts as soon as a read fails, while parts may
     * still be on their way; those could land after the abort. A failed read reports itself
     * first, so the parts in flight are cancelled before minio aborts.
     */
    @Test
    fun `a failed read is reported before it is rethrown`() {
        var reported = 0
        val stream = StoppableInputStream(FailingStream()) { reported++ }

        assertFailsWith<IOException> { stream.read(ByteArray(2), 0, 2) }

        assertEquals(1, reported)
    }

    private class FailingStream : InputStream() {
        override fun read(): Int = throw IOException("the file was deleted")
        override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("the file was deleted")
    }
}
