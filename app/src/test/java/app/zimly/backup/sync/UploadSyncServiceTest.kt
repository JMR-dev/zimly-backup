package app.zimly.backup.sync

import android.net.Uri
import android.util.Log
import app.zimly.backup.data.media.ContentObject
import app.zimly.backup.data.media.LocalContentResolver
import app.zimly.backup.data.s3.MinioRepository
import app.zimly.backup.data.s3.S3Object
import app.zimly.backup.SILO_IMAGE
import app.zimly.backup.readTestResource
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.hamcrest.CoreMatchers
import org.hamcrest.MatcherAssert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.testcontainers.containers.MinIOContainer
import java.io.ByteArrayInputStream
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals

class UploadSyncServiceTest {
    private val minioUser = "test"
    private val minioPwd = "testtest"

    private lateinit var localContentResolver: LocalContentResolver
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
        every { Log.isLoggable(any(), any()) } returns false

        this.localContentResolver = mockk()

        val bucket = "test-bucket"
        minioRepository = MinioRepository(minioContainer.s3URL, minioUser, minioPwd, bucket)

        runBlocking { minioRepository.createBucket(bucket) }
    }

    @Test
    fun localDiff() {
        val uri = mockk<Uri>()
        every { localContentResolver.listObjects() } returns listOf(
            ContentObject(
                "path/name",
                "path/name",
                1234,
                "jpeg",
                uri,
                Instant.now().toEpochMilli(),
            )
        )

        val ss = UploadSyncService(minioRepository, localContentResolver)

        val diff = ss.calculateDiff()
        MatcherAssert.assertThat(diff.remotes.size, CoreMatchers.`is`(0))
        MatcherAssert.assertThat(diff.locals.size, CoreMatchers.`is`(1))
        MatcherAssert.assertThat(diff.diff.size, CoreMatchers.`is`(1))
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

        val uri1 = mockk<Uri>()
        val uri2 = mockk<Uri>()
        val obj1 = ContentObject(
            path = image1,
            image1,
            size1,
            "image/png",
            uri1,
            Instant.now().toEpochMilli(),
        )
        val obj2 = ContentObject(
            path = image2,
            image1,
            size2,
            "image/png",
            uri2,
            Instant.now().toEpochMilli(),
        )
        every { localContentResolver.listObjects() } returns listOf(obj1, obj2)
        // Each open, a retry included, gets a fresh stream of its own file.
        every { localContentResolver.getInputStream(uri1) } answers { ByteArrayInputStream(bytes1) }
        every { localContentResolver.getInputStream(uri2) } answers { ByteArrayInputStream(bytes2) }

        val ss = UploadSyncService(minioRepository, localContentResolver)

        // WHEN
        val res = ss.synchronize().last()

        // THEN
        MatcherAssert.assertThat(res.transferredFiles, CoreMatchers.`is`(2))
        MatcherAssert.assertThat(res.transferredBytes, CoreMatchers.`is`(totalSize))
        MatcherAssert.assertThat(res.percentage, CoreMatchers.`is`(1f))

        //Thread.sleep(3000)
        val objs = minioRepository.listObjects()
        MatcherAssert.assertThat(objs.size, CoreMatchers.`is`(2))
        MatcherAssert.assertThat(objs.map { it.name }, CoreMatchers.hasItems(image1, image2))

        val name = minioRepository.get(image1).`object`()
        MatcherAssert.assertThat(name, CoreMatchers.`is`(image1))

    }

    @Test
    fun onlyLocal() {
        val locales = listOf(
            ContentObject(
                "Camera/test/test_image.png",
                "Camera/test/test_image.png",
                123,
                "image/png",
                mockk<Uri>(),
                Instant.now().toEpochMilli()
            )
        )
        val remotes = emptyList<S3Object>()
        val uploads = UploadSyncService.calculateUploads(locales, remotes)

        assertEquals(1, uploads.size)
    }

    @Test
    fun localNotNewer() {
        val earlier = ZonedDateTime.of(
            LocalDateTime.of(2025, 6, 18, 15, 0),
            ZoneId.of("Europe/Paris")
        )
        val later = earlier.plus(Duration.ofHours(2))
        val locales = listOf(
            ContentObject(
                "Camera/test_image.png",
                "Camera/test_image.png",
                123,
                "image/png",
                mockk<Uri>(),
                earlier.toInstant().toEpochMilli()
            )

        )
        val remotes = listOf(
            S3Object(
                name = "Camera/test_image.png",
                size = 123,
                checksum = "awd",
                modified = later,
            )
        )
        val uploads = UploadSyncService.calculateUploads(locales, remotes)

        assertEquals(0, uploads.size)
    }

    @Test
    fun localNewer() {
        val earlier = ZonedDateTime.of(
            LocalDateTime.of(2025, 6, 18, 15, 0),
            ZoneId.of("Europe/Paris")
        )
        val later = earlier.plus(Duration.ofHours(2))
        val locals = listOf(
            ContentObject(
                "Camera/test_image.png",
                "Camera/test_image.png",
                123,
                "image/png",
                mockk<Uri>(),
                later.toInstant().toEpochMilli()
            )

        )
        val remotes = listOf(
            S3Object(
                name = "Camera/test_image.png",
                size = 123,
                checksum = "awd",
                modified = earlier,
            )
        )
        val uploads = UploadSyncService.calculateUploads(locals, remotes)

        assertEquals(1, uploads.size)
    }
}
