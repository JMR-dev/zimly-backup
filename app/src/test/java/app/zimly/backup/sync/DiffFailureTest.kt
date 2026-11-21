package app.zimly.backup.sync

import android.util.Log
import app.zimly.backup.data.media.LocalContentResolver
import app.zimly.backup.data.media.WriteableContentResolver
import app.zimly.backup.data.s3.S3Repository
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.io.IOException
import kotlin.test.assertFailsWith

/**
 * Listing the bucket is the first network call of every sync. SyncWorker retries only an
 * IOException, so a network error there must still be one after the diff wraps it.
 */
class DiffFailureTest {

    private val s3: S3Repository = mockk()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
        every { s3.listObjects() } throws IOException("connection reset")
    }

    @Test
    fun `a network error while listing for a download is an IO error`() = runTest {
        val local: WriteableContentResolver = mockk()

        assertFailsWith<IOException> { DownloadSyncService(s3, local, FakeDownloadsInProgress(), samplePeriod = 1).synchronize().toList() }
    }

    @Test
    fun `a network error while listing for an upload is an IO error`() = runTest {
        val local: LocalContentResolver = mockk()

        assertFailsWith<IOException> { UploadSyncService(s3, local).synchronize().toList() }
    }
}
