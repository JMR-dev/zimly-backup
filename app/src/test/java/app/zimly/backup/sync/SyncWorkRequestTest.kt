package app.zimly.backup.sync

import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * [SyncWorker.request] is the one place the app's sync work request is built, so the UI and the
 * end-to-end tests enqueue exactly the same work.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncWorkRequestTest {

    @Test
    fun `request syncs the given remote with the app's worker`() {
        val spec = SyncWorker.request(42).workSpec

        assertEquals(SyncWorker::class.java.name, spec.workerClassName)
        assertEquals(42, spec.input.getInt(SyncInputs.REMOTE_CONFIGURATION_ID, -1))
    }

    @Test
    fun `request waits for a network and retries with exponential backoff`() {
        val spec = SyncWorker.request(42).workSpec

        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(Duration.ofMinutes(1).toMillis(), spec.backoffDelayDuration)
    }
}
