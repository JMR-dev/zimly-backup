package app.zimly.backup.e2e

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.zimly.backup.data.db.ZimlyDatabase
import app.zimly.backup.data.db.remote.Remote
import app.zimly.backup.data.db.remote.SyncDirection
import app.zimly.backup.data.media.ContentType
import app.zimly.backup.sync.SyncOutputs
import app.zimly.backup.sync.SyncWorker
import io.minio.GetObjectArgs
import io.minio.MinioClient
import io.minio.StatObjectArgs
import io.minio.messages.RetentionMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes

/**
 * End to end: the app's own sync uploads a photo and a video from MediaStore to an Object Lock
 * bucket. It goes through the real path: a [Remote] in the app's database, [SyncWorker.request]
 * enqueued on the app's WorkManager, the upload sync service and the S3 client. Then it checks over
 * S3 that both objects arrived intact and locked.
 *
 * Runs only when a host script has written its configuration to [CONFIG_PATH] on the device
 * (see ~/.claude/scripts/zimly-backup/2026-11-20-*-android-e2e.sh); otherwise it is skipped.
 * The file is JSON with endpoint, region, bucket, key and secret. It's read through the shell user
 * (apps can't read /data/local/tmp) and deleted straight after, so credentials never appear on a
 * command line or in instrumentation arguments.
 */
@RunWith(AndroidJUnit4::class)
class ObjectLockSyncE2ETest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.ACCESS_MEDIA_LOCATION,
    )

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val collection = "ZimlyE2E${System.currentTimeMillis()}"
    private val remoteId = REMOTE_ID_BASE + Random.nextInt(1_000_000)
    private val seededMedia = mutableListOf<Uri>()

    private lateinit var config: E2EConfig

    @Before
    fun setUp() {
        val loaded = readAndDeleteConfig()
        assumeTrue("No end-to-end configuration at $CONFIG_PATH; skipping.", loaded != null)
        config = loaded!!
    }

    @After
    fun tearDown() = runBlocking {
        seededMedia.forEach { context.contentResolver.delete(it, null, null) }
        ZimlyDatabase.getInstance(context).remoteDao().deleteById(remoteId)
    }

    @Test
    fun syncWorkerUploadsPhotoAndVideoToLockedBucket() = runBlocking {
        val photo = noisePng()
        val video = Random(VIDEO_SEED).nextBytes(VIDEO_SIZE)
        seedMedia(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), PHOTO_NAME, "image/png", photo)
        seedMedia(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), VIDEO_NAME, "video/mp4", video)
        insertRemote()

        val result = runSyncWorker()

        assertEquals(
            "Sync worker failed: ${result.outputData.getString(SyncOutputs.ERROR)}",
            WorkInfo.State.SUCCEEDED, result.state
        )
        assertStoredAndLocked("$collection/$PHOTO_NAME", "image/png", photo)
        assertStoredAndLocked("$collection/$VIDEO_NAME", "video/mp4", video)
    }

    /** Enqueues exactly the request the app enqueues and waits for it to finish. */
    private suspend fun runSyncWorker(): WorkInfo {
        val workManager = WorkManager.getInstance(context)
        val request = SyncWorker.request(remoteId)
        workManager.enqueue(request)
        val info = withTimeout(SYNC_TIMEOUT) {
            workManager.getWorkInfoByIdFlow(request.id).first { it != null && (it.state.isFinished || it.isRetrying()) }!!
        }
        if (info.isRetrying()) {
            workManager.cancelWorkById(request.id)
            throw AssertionError("Sync worker asked to retry (a network/IO error); see logcat tag SyncWorker.")
        }
        return info
    }

    private fun WorkInfo.isRetrying() = state == WorkInfo.State.ENQUEUED && runAttemptCount > 0

    /** The remote configuration the app's editor would save for this collection and bucket. */
    private suspend fun insertRemote() {
        val remote = Remote(
            uid = remoteId,
            name = "Object Lock E2E",
            url = config.endpoint,
            key = config.key,
            secret = config.secret,
            bucket = config.bucket,
            region = config.region,
            virtualHostedStyle = false,
            contentType = ContentType.MEDIA,
            contentUri = collection,
            direction = SyncDirection.UPLOAD,
        )
        ZimlyDatabase.getInstance(context).remoteDao().insert(remote)
    }

    /** Adds a file to MediaStore under DCIM/<collection>/, so it belongs to that media bucket. */
    private fun seedMedia(collectionUri: Uri, name: String, mimeType: String, bytes: ByteArray) {
        val resolver = context.contentResolver
        val pending = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/$collection/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(collectionUri, pending)) { "MediaStore refused $name" }
        seededMedia += uri
        checkNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    private fun assertStoredAndLocked(objectName: String, contentType: String, expected: ByteArray) {
        val s3 = MinioClient.builder()
            .endpoint(config.endpoint)
            .region(config.region)
            .credentials(config.key, config.secret)
            .build()
        val stat = s3.statObject(StatObjectArgs.builder().bucket(config.bucket).`object`(objectName).build())
        val stored = s3.getObject(GetObjectArgs.builder().bucket(config.bucket).`object`(objectName).build())
            .use { it.readBytes() }

        assertEquals("content type of $objectName", contentType, stat.contentType())
        assertArrayEquals("bytes of $objectName", expected, stored)
        assertEquals("lock mode of $objectName", RetentionMode.GOVERNANCE, stat.lockMode())
    }

    /** A real PNG of random pixels: about 0.5 MiB, below the 5 MiB part size. */
    private fun noisePng(): ByteArray {
        val random = Random(PHOTO_SEED)
        val pixels = IntArray(PHOTO_SIDE * PHOTO_SIDE) { random.nextInt() or OPAQUE }
        val bitmap = Bitmap.createBitmap(pixels, PHOTO_SIDE, PHOTO_SIDE, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private data class E2EConfig(
        val endpoint: String,
        val region: String,
        val bucket: String,
        val key: String,
        val secret: String,
    )

    /** Reads the host-provided JSON as the shell user, then deletes it. Null if it isn't there. */
    private fun readAndDeleteConfig(): E2EConfig? {
        val json = shell("cat $CONFIG_PATH").trim()
        shell("rm -f $CONFIG_PATH")
        if (json.isEmpty()) return null
        val parsed = JSONObject(json)
        return E2EConfig(
            endpoint = parsed.getString("endpoint"),
            region = parsed.getString("region"),
            bucket = parsed.getString("bucket"),
            key = parsed.getString("key"),
            secret = parsed.getString("secret"),
        )
    }

    private fun shell(command: String): String {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes().decodeToString() }
    }

    private companion object {
        const val CONFIG_PATH = "/data/local/tmp/zimly-e2e.json"
        const val REMOTE_ID_BASE = 900_000_000
        const val PHOTO_NAME = "photo.png"
        const val VIDEO_NAME = "video.mp4"
        const val PHOTO_SIDE = 420
        const val PHOTO_SEED = 1
        const val VIDEO_SEED = 2
        const val OPAQUE = 0xFF000000.toInt()

        /** Over the 5 MiB part size, so the video goes up as a multipart upload. */
        const val VIDEO_SIZE = 6 * 1024 * 1024 + 321
        val SYNC_TIMEOUT = 10.minutes
    }
}
