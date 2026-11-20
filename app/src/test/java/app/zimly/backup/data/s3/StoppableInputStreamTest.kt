package app.zimly.backup.data.s3

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
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
}
