package app.zimly.backup.data.s3

import okio.Buffer
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A download reads its body about 8 KiB at a time. Reporting each read as progress roughly
 * doubled download CPU on Android's runtime, so progress goes out in larger steps, and the last
 * bytes go out at the end of the body.
 */
class CountedSourceTest {

    @Test
    fun `progress goes out in steps of at least 256 KiB, and every byte is counted`() {
        val size = 1024L * 1024 + 100
        val steps = mutableListOf<Long>()

        Buffer().write(ByteArray(size.toInt())).counted { steps += it }.buffer().readByteArray()

        assertEquals(size, steps.sum())
        assertTrue("$steps", steps.dropLast(1).all { it >= PROGRESS_STEP_BYTES })
        assertTrue("${steps.size} steps", steps.size <= (size / PROGRESS_STEP_BYTES).toInt() + 1)
    }

    @Test
    fun `a body smaller than a step is counted at its end`() {
        val steps = mutableListOf<Long>()

        Buffer().write(ByteArray(100)).counted { steps += it }.buffer().readByteArray()

        assertEquals(listOf(100L), steps)
    }
}
