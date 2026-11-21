package app.zimly.backup.sync

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker.Result.Success
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.google.common.util.concurrent.Futures
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The sync asks to run in the foreground, so Android doesn't stop it after 10 minutes, and it
 * still finishes when Android refuses (Android 12+ refuses a foreground start from the background).
 */
@RunWith(AndroidJUnit4::class)
class SyncWorkerForegroundTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val syncService: SyncService = mockk()
        every { syncService.synchronize() } returns flowOf(
            progress(0f), progress(0.004f), progress(0.5f), progress(1f)
        )
        mockkObject(SyncWorker)
        coEvery { SyncWorker.initSyncService(any(), any()) } returns syncService
        every { SyncWorker.mayStartForegroundService(any()) } returns true
    }

    @After
    fun tearDown() {
        unmockkObject(SyncWorker)
    }

    @Test
    fun syncRunsAsADataSyncForegroundJobAndShowsItsProgress() {
        val updates = CopyOnWriteArrayList<ForegroundInfo>()

        val result = runSync { _, _, info ->
            updates += info
            Futures.immediateVoidFuture()
        }

        assertTrue(result is Success)
        assertTrue(updates.all { it.foregroundServiceType == ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC })
        assertTrue(updates.all { it.notificationId == REMOTE_ID })
        // Indeterminate at the start, then each whole percent once (0.004 rounds to 0, a repeat).
        assertEquals(listOf(null, 0, 50, 100), updates.map { it.notification.percent() })
    }

    @Test
    fun syncFinishesWhenAndroidRefusesTheForeground() {
        val attempts = CopyOnWriteArrayList<ForegroundInfo>()

        val result = runSync { _, _, info ->
            attempts += info
            Futures.immediateFailedFuture(IllegalStateException("foreground start not allowed"))
        }

        assertTrue(result is Success)
        // While the app is in the foreground it asks again at each progress step: the start and 0, 50, 100.
        assertEquals(4, attempts.size)
    }

    /**
     * A refused start still leaves the work in WorkManager's foreground bookkeeping, where it
     * ignores Android's stop requests, so the worker doesn't ask when Android won't allow it.
     */
    @Test
    fun syncDoesNotAskForTheForegroundFromTheBackground() {
        every { SyncWorker.mayStartForegroundService(any()) } returns false
        val attempts = CopyOnWriteArrayList<ForegroundInfo>()

        val result = runSync { _, _, info ->
            attempts += info
            Futures.immediateVoidFuture()
        }

        assertTrue(result is Success)
        assertTrue("no foreground requests", attempts.isEmpty())
    }

    /** A sync that started in the background moves to the foreground once the user opens the app. */
    @Test
    fun syncMovesToTheForegroundWhenTheAppComesBack() {
        every { SyncWorker.mayStartForegroundService(any()) } returnsMany listOf(false, true)
        val updates = CopyOnWriteArrayList<ForegroundInfo>()

        val result = runSync { _, _, info ->
            updates += info
            Futures.immediateVoidFuture()
        }

        assertTrue(result is Success)
        // Not at the start; from the first progress step on, each whole percent once.
        assertEquals(listOf(0, 50, 100), updates.map { it.notification.percent() })
    }

    private fun runSync(foreground: ForegroundUpdater) = runBlocking {
        TestListenableWorkerBuilder<SyncWorker>(context)
            .setInputData(workDataOf(SyncInputs.REMOTE_CONFIGURATION_ID to REMOTE_ID))
            .setForegroundUpdater(foreground)
            .build()
            .doWork()
    }

    private fun progress(percentage: Float) = SyncProgress(
        transferredBytes = (percentage * TOTAL_BYTES).toLong(),
        transferredFiles = 0,
        percentage = percentage,
        bytesPerSecond = null,
        totalFiles = 1,
        totalBytes = TOTAL_BYTES,
    )

    /** The notification's progress percent, or null while it is indeterminate. */
    private fun Notification.percent(): Int? =
        if (extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)) null
        else extras.getInt(Notification.EXTRA_PROGRESS)

    private companion object {
        const val REMOTE_ID = 7
        const val TOTAL_BYTES = 1000L
    }
}
