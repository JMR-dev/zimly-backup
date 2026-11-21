package app.zimly.backup.sync

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import app.zimly.backup.data.db.ZimlyDatabase
import app.zimly.backup.data.db.remote.Remote
import app.zimly.backup.data.db.remote.SyncDirection
import app.zimly.backup.data.db.retry.NextTry
import app.zimly.backup.data.media.ContentType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import kotlin.math.abs
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A sync that fails with a network error says so while it waits: it records the error and the
 * earliest next try for the sync screen, and posts a notification with a Cancel action. It gives
 * up, and says so, after 24 hours of failing. Runs against the app's database, with a remote id
 * no real remote uses, deleted afterwards.
 */
@RunWith(AndroidJUnit4::class)
class SyncWorkerRetryTest {

    private lateinit var context: Context
    private lateinit var db: ZimlyDatabase
    private val syncService: SyncService = mockk()

    @Before
    fun setUp() = runBlocking<Unit> {
        context = ApplicationProvider.getApplicationContext()
        allowNotifications()
        db = ZimlyDatabase.getInstance(context)
        db.remoteDao().insert(remote())
        mockkObject(SyncWorker)
        coEvery { SyncWorker.initSyncService(any(), any()) } returns syncService
        every { SyncWorker.mayStartForegroundService(any()) } returns false
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        unmockkObject(SyncWorker)
        WorkManager.getInstance(context).cancelUniqueWork(SyncWorker.uniqueWorkName(REMOTE_ID)).result.get()
        db.remoteDao().deleteById(REMOTE_ID)
        RetryNotification.dismiss(context, REMOTE_ID)
    }

    @Test
    fun aNetworkErrorRecordsTheRetryAndNotifies() = runBlocking<Unit> {
        every { syncService.synchronize() } returns flow { throw IOException("connection reset") }
        val before = System.currentTimeMillis()

        val result = runSync(runAttemptCount = 2)

        assertTrue("$result", result is Result.Retry)
        val nextTry = checkNotNull(db.syncRetryDao().load(REMOTE_ID)?.nextTry) { "no retry recorded" }
        assertEquals("connection reset", nextTry.lastError)
        // The third run (count 2) waits 4 minutes.
        assertTrue("$nextTry", nextTry.at in (before + 4.minutes.inWholeMilliseconds)..(System.currentTimeMillis() + 4.minutes.inWholeMilliseconds))
        assertNotNull("retry notification", activeRetryNotification())
    }

    @Test
    fun aNetworkErrorAfter24HoursOfFailingGivesUpAndNotifies() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        db.syncRetryDao().recordRetry(REMOTE_ID, now - 25.hours.inWholeMilliseconds, NextTry("connection reset", now))
        every { syncService.synchronize() } returns flow { throw IOException("connection reset") }

        val result = runSync(runAttemptCount = 9)

        assertEquals(
            "Gave up after 24 hours of failed tries: connection reset",
            (result as Result.Failure).outputData.getString(SyncOutputs.ERROR),
        )
        assertNull("forgotten", db.syncRetryDao().load(REMOTE_ID))
        assertNotNull("failure notification", activeRetryNotification())
    }

    @Test
    fun aSuccessfulRunForgetsEarlierFailures() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        db.syncRetryDao().recordRetry(REMOTE_ID, now - 1.hours.inWholeMilliseconds, NextTry("connection reset", now))
        RetryNotification.post(context, REMOTE_ID, RetryNotification.failed(context, "earlier"))
        every { syncService.synchronize() } returns flowOf(progress())

        val result = runSync(runAttemptCount = 3)

        assertTrue("$result", result is Result.Success)
        assertNull("forgotten", db.syncRetryDao().load(REMOTE_ID))
        assertNull("notification gone", activeRetryNotification())
    }

    /**
     * The next-try time is computed from WorkManager's backoff formula, because WorkInfo doesn't
     * have it yet when the worker returns. Through the real WorkManager, they must agree.
     */
    @Test
    fun theRecordedNextTryMatchesWorkManagersSchedule() = runBlocking<Unit> {
        every { syncService.synchronize() } returns flow { throw IOException("connection reset") }
        val workManager = WorkManager.getInstance(context)
        val request = SyncWorker.request(REMOTE_ID)

        workManager.enqueueUniqueWork(SyncWorker.uniqueWorkName(REMOTE_ID), ExistingWorkPolicy.REPLACE, request)
        val waiting = withTimeout(60.seconds) {
            workManager.getWorkInfoByIdFlow(request.id).first { it != null && it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount == 1 }
        }

        val recorded = checkNotNull(db.syncRetryDao().load(REMOTE_ID)?.nextTry) { "no retry recorded" }.at
        val scheduled = checkNotNull(waiting).nextScheduleTimeMillis
        assertTrue("recorded $recorded, scheduled $scheduled", abs(recorded - scheduled) < 5.seconds.inWholeMilliseconds)
    }

    private suspend fun runSync(runAttemptCount: Int): Result =
        TestListenableWorkerBuilder<SyncWorker>(context)
            .setInputData(workDataOf(SyncInputs.REMOTE_CONFIGURATION_ID to REMOTE_ID))
            .setRunAttemptCount(runAttemptCount)
            .build()
            .doWork()

    private fun activeRetryNotification() =
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .firstOrNull { it.id == REMOTE_ID && it.tag == RetryNotification.TAG }

    private fun allowNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun remote() = Remote(
        REMOTE_ID, "Retry test", "https://s3.example.com", "key", "secret", "bucket", null,
        virtualHostedStyle = false, contentType = ContentType.MEDIA, contentUri = "Camera",
        direction = SyncDirection.UPLOAD,
    )

    private fun progress() = SyncProgress(
        transferredBytes = 1, transferredFiles = 1, percentage = 1f, bytesPerSecond = null, totalFiles = 1, totalBytes = 1,
    )

    private companion object {
        const val REMOTE_ID = 900_001
    }
}
