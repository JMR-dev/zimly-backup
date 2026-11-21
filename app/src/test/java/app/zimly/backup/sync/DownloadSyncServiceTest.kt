package app.zimly.backup.sync

import android.net.Uri
import android.util.Log
import app.zimly.backup.SILO_IMAGE
import app.zimly.backup.readTestResource
import app.zimly.backup.data.media.ContentObject
import app.zimly.backup.data.media.OpenedDocument
import app.zimly.backup.data.media.WriteableContentResolver
import app.zimly.backup.data.s3.MinioRepository
import app.zimly.backup.data.s3.S3Object
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.hamcrest.CoreMatchers
import org.hamcrest.MatcherAssert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.testcontainers.containers.MinIOContainer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DownloadSyncServiceTest {
    private val minioUser = "test"
    private val minioPwd = "testtest"

    private lateinit var localContentResolver: WriteableContentResolver
    private lateinit var minioRepository: MinioRepository

    @get:Rule
    val minioContainer: MinIOContainer = MinIOContainer(SILO_IMAGE)
        .withUserName(minioUser)
        .withPassword(minioPwd)

    @Before
    fun setUp() {

        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.isLoggable(any(), any()) } returns false

        this.localContentResolver = mockk()

        val bucket = "test-bucket"
        minioRepository = MinioRepository(minioContainer.s3URL, minioUser, minioPwd, bucket)

        runBlocking { minioRepository.createBucket(bucket) }
    }

    @Test
    fun synchronize() = runTest {

        // GIVEN
        val image1 = "test_image.png"
        val image2 = "test_image2.png"

        val path1 = "/testdata/$image1"
        val path2 = "/testdata/$image2"
        val bytes1 = readTestResource(path1)
        val bytes2 = readTestResource(path2)
        val size1 = bytes1.size.toLong()
        val size2 = bytes2.size.toLong()
        val totalSize = size1 + size2

        minioRepository.put({ ByteArrayInputStream(bytes1) }, image1, "image/png", size1).last()
        minioRepository.put({ ByteArrayInputStream(bytes2) }, image2, "image/png", size2).last()

        every { localContentResolver.listObjects() } returns emptyList()
        val out1 = ByteArrayOutputStream()
        val out2 = ByteArrayOutputStream()
        every { localContentResolver.openForWriting(any()) } returns
            OpenedDocument(mockk(), out1) andThen OpenedDocument(mockk(), out2)

        val ss = DownloadSyncService(minioRepository, localContentResolver, FakeDownloadsInProgress())

        // WHEN
        val res = ss.synchronize().toList().last()

        // THEN
        MatcherAssert.assertThat(res.transferredFiles, CoreMatchers.`is`(2))
        MatcherAssert.assertThat(res.transferredBytes, CoreMatchers.`is`(totalSize))
        MatcherAssert.assertThat(res.percentage, CoreMatchers.`is`(1f))

        assertEquals(out1.size(), size1.toInt())
        assertEquals(out2.size(), size2.toInt())

        // Cleanup
        minioRepository.remove(image1)
        minioRepository.remove(image2)
    }

    @Test
    fun fail() = runTest {

        // GIVEN
        minioRepository = mockk()
        every { localContentResolver.listObjects() } returns emptyList()
        every { minioRepository.listObjects() } throws Exception("Boom")

        val ss = DownloadSyncService(minioRepository, localContentResolver, FakeDownloadsInProgress())

        // WHEN
        val flow = ss.synchronize()

        // THEN
        val thrown = assertFailsWith<Exception> {
            flow.collect {} // will throw
        }
        assertEquals("Failed to create remote diff: Boom", thrown.message)
    }

    @Test
    fun onlyRemote() {
        val remotes = listOf(
            S3Object(
                name = "test/test_image.png",
                size = 123,
                checksum = "awd",
                modified = ZonedDateTime.now(),
            )
        )
        val locales = emptyList<ContentObject>()
        val downloads = DownloadSyncService.calculateDownloads(remotes, locales)

        assertEquals(1, downloads.size)
    }

    @Test
    fun remoteOlder() {
        val earlier = ZonedDateTime.of(
            LocalDateTime.of(2025, 6, 18, 15, 0),
            ZoneId.of("Europe/Paris")
        )
        val later = earlier.plus(Duration.ofHours(2))
        val remotes = listOf(
            S3Object(
                name = "test/test_image.png",
                size = 123,
                checksum = "awd",
                modified = earlier,
            )
        )
        val locales = listOf(
            ContentObject(
                "/absolute/test/test_image.png",
                "test/test_image.png",
                123,
                "image/png",
                mockk<Uri>(),
                later.toInstant().toEpochMilli()
            )

        )
        val downloads = DownloadSyncService.calculateDownloads(remotes, locales)

        assertEquals(0, downloads.size)
    }

    @Test
    fun remoteNewer() {
        val earlier = ZonedDateTime.of(
            LocalDateTime.of(2025, 6, 18, 15, 0),
            ZoneId.of("Europe/Paris")
        )
        val later = earlier.plus(Duration.ofHours(2))
        val remotes = listOf(
            S3Object(
                name = "test/test_image.png",
                size = 123,
                checksum = "awd",
                modified = later,
            )
        )
        val locales = listOf(
            ContentObject(
                "/absolute/test/test_image.png",
                "test/test_image.png",
                123,
                "image/png",
                mockk<Uri>(),
                earlier.toInstant().toEpochMilli()
            )

        )
        val downloads = DownloadSyncService.calculateDownloads(remotes, locales)

        assertEquals(1, downloads.size)
    }

}
