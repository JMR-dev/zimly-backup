package app.zimly.backup.e2e

import android.Manifest
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.zimly.backup.MainActivity
import app.zimly.backup.data.db.ZimlyDatabase
import app.zimly.backup.data.db.remote.Remote
import app.zimly.backup.data.db.remote.SyncDirection
import app.zimly.backup.data.media.ContentType
import app.zimly.backup.permission.permissionProvider
import app.zimly.backup.sync.SyncOutputs
import app.zimly.backup.sync.SyncWorker
import io.minio.GetObjectArgs
import io.minio.MinioClient
import io.minio.StatObjectArgs
import io.minio.messages.RetentionMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * End to end: the app's own sync uploads a photo and a video from MediaStore to an Object Lock
 * bucket. It goes through the real path: a [Remote] in the app's database, [SyncWorker.request]
 * enqueued on the app's WorkManager while the app is on screen, the upload sync service and the S3
 * client. Once the sync shows its foreground notification the app leaves the screen, as a user
 * would, and the sync has to finish in the background. Then the test checks over S3 that both
 * objects arrived intact and locked.
 *
 * Runs only when its configuration is on the device at [CONFIG_PATH]; otherwise it skips itself,
 * so ordinary `connectedAndroidTest` runs are unaffected. The file is JSON (see [E2EConfig]):
 *
 * ```json
 * {"endpoint": "https://s3.<region>.example.com", "region": "<region>", "bucket": "<bucket>",
 *  "key": "<access key>", "secret": "<secret key>",
 *  "videoBytes": 6291777, "syncTimeoutMinutes": 10, "minSyncMinutes": 0, "expectForeground": true}
 * ```
 *
 * The last four are optional. A long-upload run raises the video size and timeout, throttles the
 * upload, and sets `minSyncMinutes` to prove the sync outlived Android's 10-minute limit for
 * background work. A control run on code without the foreground job turns `expectForeground` off.
 *
 * The bucket needs Object Lock with a default GOVERNANCE retention, and the key needs read, write
 * and read-retention access to it. Write the file through adb's stdin, so no credential appears
 * on a command line, then run only this test:
 *
 * ```sh
 * jq -nc ... | adb shell 'umask 077; cat > /data/local/tmp/zimly-e2e.json'
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=app.zimly.backup.e2e.ObjectLockSyncE2ETest
 * ```
 *
 * The test reads the file as the shell user (apps can't read /data/local/tmp) and deletes it
 * straight away, so the credentials never reach instrumentation arguments either.
 */
@RunWith(AndroidJUnit4::class)
class ObjectLockSyncE2ETest {

    /** The media permissions the app asks for on this Android version, and the notification one. */
    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(*permissionProvider(), *notificationPermission())

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val collection = "ZimlyE2E${System.currentTimeMillis()}"
    private val remoteId = REMOTE_ID_BASE + Random.nextInt(1_000_000)
    private val seededMedia = mutableListOf<Uri>()
    private var syncId: UUID? = null

    private lateinit var config: E2EConfig

    @Before
    fun setUp() {
        val loaded = E2EConfig.readAndDelete()
        assumeTrue("No end-to-end configuration at $CONFIG_PATH; skipping.", loaded != null)
        config = loaded!!
    }

    @After
    fun tearDown() = runBlocking {
        // A failed run can leave its sync uploading: stop it before deleting what it uploads.
        syncId?.let { WorkManager.getInstance(context).cancelWorkById(it).result.get(CANCEL_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        seededMedia.forEach { context.contentResolver.delete(it, null, null) }
        ZimlyDatabase.getInstance(context).remoteDao().deleteById(remoteId)
    }

    @Test
    fun syncWorkerUploadsPhotoAndVideoToLockedBucket() = runBlocking {
        val photo = seedMedia(IMAGES, PHOTO_NAME, "image/png") { output ->
            noisePng().also { output.write(it) }.let(::digestOf)
        }
        val video = seedMedia(VIDEOS, VIDEO_NAME, "video/mp4") { output ->
            writeRandom(output, config.videoBytes, VIDEO_SEED)
        }
        insertRemote()

        val (result, took) = runSyncFromTheApp()

        assertEquals(
            "Sync worker failed: ${result.outputData.getString(SyncOutputs.ERROR)}",
            WorkInfo.State.SUCCEEDED, result.state
        )
        assertTrue("The sync took $took, expected at least ${config.minSync}", took >= config.minSync)
        assertStoredAndLocked("$collection/$PHOTO_NAME", "image/png", photo)
        assertStoredAndLocked("$collection/$VIDEO_NAME", "video/mp4", video)
    }

    /**
     * Starts the sync as the sync screen does, with the app on screen, which lets the worker run in
     * the foreground. Once the sync has started (and, unless [E2EConfig.expectForeground] is off,
     * shows its notification), the app leaves the screen. Returns the finished work and how long
     * the sync took.
     */
    private suspend fun runSyncFromTheApp(): Pair<WorkInfo, Duration> {
        val workManager = WorkManager.getInstance(context)
        val request = SyncWorker.request(remoteId)
        syncId = request.id
        val started = TimeSource.Monotonic.markNow()
        ActivityScenario.launch(MainActivity::class.java).use {
            workManager.enqueue(request)
            awaitStarted(workManager, request.id)
            if (config.expectForeground) awaitSyncNotification(workManager, request.id)
        }
        val finished = awaitFinished(workManager, request.id)
        return finished to started.elapsedNow()
    }

    private suspend fun awaitStarted(workManager: WorkManager, id: UUID) {
        val started = withTimeoutOrNull(START_TIMEOUT) {
            workManager.getWorkInfoByIdFlow(id).first { it != null && it.state != WorkInfo.State.ENQUEUED }
        }
        assertNotNull("The sync never started", started)
    }

    /**
     * Waits for the sync's foreground notification, which has the remote's id. A sync can finish
     * between two looks and take its notification with it; that proves nothing either way, so it
     * is logged, not failed.
     */
    private suspend fun awaitSyncNotification(workManager: WorkManager, id: UUID) {
        val notifications = context.getSystemService(NotificationManager::class.java)
        val shown = withTimeoutOrNull(NOTIFICATION_TIMEOUT) {
            // Ends when the notification shows or the sync finishes, or at the timeout.
            while (notifications.activeNotifications.none { it.id == remoteId }) {
                if (isFinished(workManager, id)) return@withTimeoutOrNull false
                delay(POLL_INTERVAL)
            }
            true
        }
        assertNotNull("The sync's foreground notification never showed", shown)
        if (shown == false) Log.w(TAG, "The sync finished before its foreground notification could be seen")
    }

    private suspend fun isFinished(workManager: WorkManager, id: UUID): Boolean =
        workManager.getWorkInfoByIdFlow(id).first()?.state?.isFinished == true

    private suspend fun awaitFinished(workManager: WorkManager, id: UUID): WorkInfo {
        val info = withTimeout(config.syncTimeout) {
            workManager.getWorkInfoByIdFlow(id).first { it != null && (it.state.isFinished || it.isRescheduled()) }!!
        }
        if (info.isRescheduled()) {
            workManager.cancelWorkById(id)
            throw AssertionError("The sync was rescheduled: a network error, or Android stopped it. See logcat tag SyncWorker.")
        }
        return info
    }

    private fun WorkInfo.isRescheduled() = state == WorkInfo.State.ENQUEUED && runAttemptCount > 0

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

    /**
     * Adds a file to MediaStore under DCIM/<collection>/, so it belongs to that media bucket.
     * [write] fills it and returns the digest of what it wrote.
     */
    private fun seedMedia(
        collectionUri: Uri,
        name: String,
        mimeType: String,
        write: (OutputStream) -> ContentDigest,
    ): ContentDigest {
        val resolver = context.contentResolver
        val pending = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/$collection/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(collectionUri, pending)) { "MediaStore refused $name" }
        seededMedia += uri
        val digest = checkNotNull(resolver.openOutputStream(uri)).use(write)
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return digest
    }

    private fun assertStoredAndLocked(objectName: String, contentType: String, expected: ContentDigest) {
        val s3 = MinioClient.builder()
            .endpoint(config.endpoint)
            .region(config.region)
            .credentials(config.key, config.secret)
            .build()
        val stat = s3.statObject(StatObjectArgs.builder().bucket(config.bucket).`object`(objectName).build())
        val stored = s3.getObject(GetObjectArgs.builder().bucket(config.bucket).`object`(objectName).build())
            .use { digestOf(it, maxBytes = expected.size) }

        assertEquals("content type of $objectName", contentType, stat.contentType())
        assertEquals("content of $objectName", expected, stored)
        assertEquals("lock mode of $objectName", RetentionMode.GOVERNANCE, stat.lockMode())
    }

    /** A real PNG of random pixels: about 0.5 MiB, below the 5 MiB part size. */
    private fun noisePng(): ByteArray {
        val random = Random(PHOTO_SEED)
        val pixels = IntArray(PHOTO_SIDE * PHOTO_SIDE) { random.nextInt() or OPAQUE }
        val bitmap = Bitmap.createBitmap(pixels, PHOTO_SIDE, PHOTO_SIDE, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private companion object {
        const val REMOTE_ID_BASE = 900_000_000
        const val PHOTO_NAME = "photo.png"
        const val VIDEO_NAME = "video.mp4"
        const val PHOTO_SIDE = 420
        const val PHOTO_SEED = 1
        const val VIDEO_SEED = 2
        const val OPAQUE = 0xFF000000.toInt()
        const val TAG = "ObjectLockSyncE2ETest"
        const val CANCEL_TIMEOUT_SECONDS = 10L
        val START_TIMEOUT = 1.minutes
        val NOTIFICATION_TIMEOUT = 1.minutes
        val POLL_INTERVAL = 100.milliseconds
        val IMAGES: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val VIDEOS: Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        /** Android 13+ needs it for the sync's foreground notification to show. */
        fun notificationPermission(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            else emptyArray()
    }
}
