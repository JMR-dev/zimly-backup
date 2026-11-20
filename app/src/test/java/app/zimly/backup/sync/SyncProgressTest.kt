package app.zimly.backup.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncProgressTest {

    @Test
    fun `a sync with no bytes to transfer is complete`() {
        assertEquals(1f, SyncProgress.percentageOf(transferredBytes = 0, totalBytes = 0))
    }

    @Test
    fun `percentage is the share of bytes transferred`() {
        assertEquals(0f, SyncProgress.percentageOf(transferredBytes = 0, totalBytes = 200))
        assertEquals(0.5f, SyncProgress.percentageOf(transferredBytes = 100, totalBytes = 200))
        assertEquals(1f, SyncProgress.percentageOf(transferredBytes = 200, totalBytes = 200))
    }
}
