package app.zimly.backup.data.s3

import org.junit.Assert.assertEquals
import org.junit.Test

class PercentageTest {

    @Test
    fun `a transfer with no bytes is complete`() {
        assertEquals(1f, percentageOf(transferredBytes = 0, totalBytes = 0))
    }

    @Test
    fun `percentage is the share of bytes transferred`() {
        assertEquals(0f, percentageOf(transferredBytes = 0, totalBytes = 200))
        assertEquals(0.5f, percentageOf(transferredBytes = 100, totalBytes = 200))
        assertEquals(1f, percentageOf(transferredBytes = 200, totalBytes = 200))
    }
}
