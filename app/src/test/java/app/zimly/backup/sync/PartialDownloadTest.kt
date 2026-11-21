package app.zimly.backup.sync

import android.util.Log
import app.zimly.backup.data.media.WriteableContentResolver
import app.zimly.backup.data.s3.Progress
import app.zimly.backup.data.s3.S3Object
import app.zimly.backup.data.s3.S3Repository
import io.minio.StatObjectResponse
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.ZonedDateTime
import kotlin.test.assertFailsWith
import kotlin.time.TimeSource

/**
 * A download that fails partway leaves a cut-off file whose timestamp is newer than the remote's,
 * so later syncs would skip it for good. The download sync deletes it, and still fails.
 */
class PartialDownloadTest {

    private val s3: S3Repository = mockk()
    private val local: WriteableContentResolver = mockk(relaxUnitFun = true)

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0

        every { s3.listObjects() } returns listOf(S3Object(PATH, SIZE, "etag", ZonedDateTime.now()))
        every { local.listObjects() } returns emptyList()
        coEvery { s3.stat(PATH) } returns stat()
        every { local.createOutputStream(PATH, CONTENT_TYPE) } returns ByteArrayOutputStream()
    }

    @Test
    fun `a failed download deletes its partial file and fails the sync`() = runTest {
        coEvery { s3.get(PATH, SIZE, any()) } returns failingDownload()

        assertFailsWith<IOException> { sync() }

        verify(exactly = 1) { local.delete(PATH) }
    }

    @Test
    fun `a completed download keeps its file`() = runTest {
        coEvery { s3.get(PATH, SIZE, any()) } returns flowOf(progress(SIZE))

        sync()

        verify(exactly = 0) { local.delete(any()) }
    }

    @Test
    fun `a failure to delete is attached to the download error`() = runTest {
        coEvery { s3.get(PATH, SIZE, any()) } returns failingDownload()
        every { local.delete(PATH) } throws IOException("read-only storage")

        val error = assertFailsWith<IOException> { sync() }

        assertEquals("connection reset", error.message)
        assertEquals(listOf("read-only storage"), suppressedAlongCauses(error).map { it.message })
    }

    /**
     * Coroutines' debug mode, on in tests, rethrows a copy of an exception with the original as
     * its cause, so the suppressed error may sit one cause down.
     */
    private fun suppressedAlongCauses(error: Throwable): List<Throwable> =
        generateSequence(error) { it.cause }
            .take(MAX_CAUSES)
            .flatMap { it.suppressed.asSequence() }
            .toList()

    private suspend fun sync() = DownloadSyncService(s3, local, samplePeriod = 1).synchronize().toList()

    private fun failingDownload(): Flow<Progress> = flow {
        emit(progress(SIZE / 2))
        throw IOException("connection reset")
    }

    private fun stat(): StatObjectResponse = mockk {
        every { `object`() } returns PATH
        every { size() } returns SIZE
        every { contentType() } returns CONTENT_TYPE
    }

    private fun progress(readBytes: Long) =
        Progress(readBytes, readBytes, readBytes.toFloat() / SIZE, SIZE, null, TimeSource.Monotonic.markNow())

    private companion object {
        const val PATH = "Camera/photo.jpg"
        const val CONTENT_TYPE = "image/jpeg"
        const val SIZE = 1000L
        const val MAX_CAUSES = 10
    }
}
