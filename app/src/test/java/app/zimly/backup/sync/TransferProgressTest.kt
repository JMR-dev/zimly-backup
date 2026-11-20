package app.zimly.backup.sync

import app.zimly.backup.data.s3.Progress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import kotlin.test.assertFailsWith
import kotlin.time.TimeSource

class TransferProgressTest {

    private val start = TimeSource.Monotonic.markNow()

    @Test
    fun `a file counts only once its transfer completes`() = runTest {
        val counts = twoFiles().toSyncProgress(totalFiles = 2, totalBytes = 35)
            .map { it.transferredFiles }
            .toList()

        // The start, file 1's two steps, file 1 done, file 2's step, file 2 done.
        assertEquals(listOf(0, 0, 0, 1, 1, 2), counts)
    }

    @Test
    fun `bytes and percentage add up across files`() = runTest {
        val last = twoFiles().toSyncProgress(totalFiles = 2, totalBytes = 35).last()

        assertEquals(35L, last.transferredBytes)
        assertEquals(1f, last.percentage)
        assertEquals(2, last.totalFiles)
        assertEquals(35L, last.totalBytes)
    }

    @Test
    fun `the first value carries the totals`() = runTest {
        val first = twoFiles().toSyncProgress(totalFiles = 2, totalBytes = 35).toList().first()

        assertEquals(SyncProgress.EMPTY.copy(totalFiles = 2, totalBytes = 35), first)
    }

    @Test
    fun `a failed transfer is not counted`() = runTest {
        val transfers = flowOf(
            flowOf(progress(10)),
            flow {
                emit(progress(5))
                throw IOException("connection reset")
            },
        )
        val seen = mutableListOf<SyncProgress>()

        assertFailsWith<IOException> {
            transfers.toSyncProgress(totalFiles = 2, totalBytes = 30).toList(seen)
        }

        assertEquals(1, seen.last().transferredFiles)
        assertEquals(15L, seen.last().transferredBytes)
    }

    /** File 1 moves 10 then 20 bytes; file 2 moves 5. */
    private fun twoFiles(): Flow<Flow<Progress>> = flowOf(
        flowOf(progress(10), progress(20)),
        flowOf(progress(5)),
    )

    private fun progress(readBytes: Long) =
        Progress(readBytes, readBytes, 0f, readBytes, BYTES_PER_SECOND, start)

    private companion object {
        const val BYTES_PER_SECOND = 100L
    }
}
