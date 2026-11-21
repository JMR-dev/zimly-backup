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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
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
        coEvery { s3.get(PATH, SIZE, any()) } answers { failingDownload(thirdArg()) }

        assertFailsWith<IOException> { sync() }

        verify(exactly = 1) { local.delete(PATH) }
    }

    /**
     * Opening the local file erases it, so a download that the server refuses (an archived object,
     * a denied key) must not open it, and must not delete the old copy it never touched.
     */
    @Test
    fun `a download refused before its file is opened keeps the local file`() = runTest {
        coEvery { s3.get(PATH, SIZE, any()) } returns flow { throw IOException("refused") }

        assertFailsWith<IOException> { sync() }

        verify(exactly = 0) { local.createOutputStream(any(), any()) }
        verify(exactly = 0) { local.delete(any()) }
    }

    /** A local file that failed to open was never written to, so it is not deleted. */
    @Test
    fun `a local file that fails to open is not deleted`() = runTest {
        every { local.createOutputStream(PATH, CONTENT_TYPE) } throws IOException("read-only file")
        coEvery { s3.get(PATH, SIZE, any()) } answers { failingDownload(thirdArg()) }

        assertFailsWith<IOException> { sync() }

        verify(exactly = 0) { local.delete(any()) }
    }

    @Test
    fun `a completed download keeps its file`() = runTest {
        coEvery { s3.get(PATH, SIZE, any()) } answers { completedDownload(thirdArg()) }

        sync()

        verify(exactly = 0) { local.delete(any()) }
    }

    /**
     * A cancel can arrive after the whole body is written and the file closed, while the last
     * progress is still on its way. The file is complete then, so it stays.
     */
    @Test
    fun `a download that fails after writing its whole file keeps it`() = runTest {
        coEvery { s3.get(PATH, SIZE, any()) } answers { failingAfterTheBody(thirdArg()) }

        assertFailsWith<IOException> { sync() }

        verify(exactly = 0) { local.delete(any()) }
    }

    /** Deleting is a blocking storage-provider call, which belongs on an IO thread. */
    @Test
    fun `a partial file is deleted off the collector's thread`() = runTest {
        coEvery { s3.get(PATH, SIZE, any()) } answers { failingDownload(thirdArg()) }
        var deletedOn: Thread? = null
        every { local.delete(PATH) } answers { deletedOn = Thread.currentThread() }

        assertFailsWith<IOException> { sync() }

        assertNotSame(Thread.currentThread(), deletedOn)
    }

    @Test
    fun `a failure to delete is attached to the download error`() = runTest {
        coEvery { s3.get(PATH, SIZE, any()) } answers { failingDownload(thirdArg()) }
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

    /** Like the real download: opens the file once the server answers, writes half, then fails. */
    private fun failingDownload(openOutput: () -> OutputStream): Flow<Progress> = flow {
        openOutput().use { it.write(ByteArray(SIZE.toInt() / 2)) }
        emit(progress(SIZE / 2))
        throw IOException("connection reset")
    }

    private fun completedDownload(openOutput: () -> OutputStream): Flow<Progress> = flow {
        openOutput().use { it.write(ByteArray(SIZE.toInt())) }
        emit(progress(SIZE))
    }

    /** Writes the whole file and closes it, then fails before the last progress is through. */
    private fun failingAfterTheBody(openOutput: () -> OutputStream): Flow<Progress> = flow {
        openOutput().use { it.write(ByteArray(SIZE.toInt())) }
        throw IOException("cancelled after the body")
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
