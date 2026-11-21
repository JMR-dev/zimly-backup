package app.zimly.backup.sync

import android.net.Uri
import android.util.Log
import app.zimly.backup.data.media.ContentObject
import app.zimly.backup.data.media.OpenedDocument
import app.zimly.backup.data.media.WriteableContentResolver
import app.zimly.backup.data.s3.Progress
import app.zimly.backup.data.s3.S3Object
import app.zimly.backup.data.s3.S3Repository
import io.minio.StatObjectResponse
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.ZonedDateTime
import kotlin.test.assertFailsWith
import kotlin.time.TimeSource

/**
 * Opening a download's file erases its old copy, and only the download itself deletes a cut-off
 * file. If the app is killed in between, the cut-off file keeps a newer timestamp than the
 * remote's, and later syncs would skip it for good. So each download is recorded before it
 * starts, and the record goes only once the file is complete, untouched or deleted; the next sync
 * downloads any key still recorded.
 */
class DownloadsInProgressTest {

    private val s3: S3Repository = mockk()
    private val local: WriteableContentResolver = mockk(relaxUnitFun = true)
    private val document: Uri = mockk()
    private val inProgress = FakeDownloadsInProgress()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0

        every { s3.listObjects() } returns listOf(S3Object(PATH, SIZE, "etag", ZonedDateTime.now()))
        every { local.listObjects() } returns emptyList()
        coEvery { s3.stat(PATH) } returns stat()
        every { local.openForWriting(PATH) } returns OpenedDocument(document, ByteArrayOutputStream())
    }

    /** A record left by a killed run makes the next sync download the file again. */
    @Test
    fun `a recorded key is downloaded although its local file looks newer`() {
        val remote = S3Object(PATH, SIZE, "etag", ZonedDateTime.now().minusDays(1))
        val cutOff = ContentObject(PATH, PATH, SIZE / 2, "image/jpeg", document, System.currentTimeMillis())

        val downloads = DownloadSyncService.calculateDownloads(listOf(remote), listOf(cutOff), setOf(PATH))

        assertEquals(listOf(PATH), downloads.map { it.name })
    }

    @Test
    fun `a download is recorded while it runs, and the record goes once the file is complete`() = runTest {
        val recordedDuringDownload = mutableListOf<Set<String>>()
        coEvery { s3.get(PATH, SIZE, any()) } answers {
            completedDownload(thirdArg()) { recordedDuringDownload += inProgress.keys() }
        }

        sync()

        assertEquals(listOf(setOf(PATH)), recordedDuringDownload)
        assertEquals(emptySet<String>(), inProgress.keys())
    }

    @Test
    fun `a cut-off file that was deleted leaves no record`() = runTest {
        coEvery { s3.get(PATH, SIZE, any()) } answers { failingDownload(thirdArg()) }

        assertFailsWith<IOException> { sync() }

        assertEquals(emptySet<String>(), inProgress.keys())
    }

    /** The cut-off file is still there, so the next sync must download it again. */
    @Test
    fun `a cut-off file that couldn't be deleted stays recorded`() = runTest {
        coEvery { s3.get(PATH, SIZE, any()) } answers { failingDownload(thirdArg()) }
        every { local.delete(document) } throws IOException("read-only storage")

        assertFailsWith<IOException> { sync() }

        assertEquals(setOf(PATH), inProgress.keys())
    }

    @Test
    fun `a download refused before its file opened leaves no record`() = runTest {
        coEvery { s3.get(PATH, SIZE, any()) } returns flow { throw IOException("refused") }

        assertFailsWith<IOException> { sync() }

        assertEquals(emptySet<String>(), inProgress.keys())
    }

    private suspend fun sync() = DownloadSyncService(s3, local, inProgress, samplePeriod = 1).synchronize().toList()

    private fun completedDownload(openOutput: () -> OutputStream, whileOpen: suspend () -> Unit): Flow<Progress> = flow {
        openOutput().use {
            whileOpen()
            it.write(ByteArray(SIZE.toInt()))
        }
        emit(progress(SIZE))
    }

    private fun failingDownload(openOutput: () -> OutputStream): Flow<Progress> = flow {
        openOutput().use { it.write(ByteArray(SIZE.toInt() / 2)) }
        throw IOException("connection reset")
    }

    private fun stat(): StatObjectResponse = mockk {
        every { `object`() } returns PATH
        every { size() } returns SIZE
    }

    private fun progress(readBytes: Long) =
        Progress(readBytes, readBytes, readBytes.toFloat() / SIZE, SIZE, null, TimeSource.Monotonic.markNow())

    private companion object {
        const val PATH = "Camera/photo.jpg"
        const val SIZE = 1000L
    }
}
