package app.zimly.backup.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.zimly.backup.data.db.ZimlyDatabase
import app.zimly.backup.data.db.remote.Remote
import app.zimly.backup.data.db.remote.SyncDirection
import app.zimly.backup.data.db.retry.NextTry
import app.zimly.backup.data.media.ContentType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A sync's failed runs, kept between runs: how long it has been failing (for the 24-hour limit),
 * and, while it waits for its next try, the error and the earliest time of that try.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncRetriesTest {

    private lateinit var db: ZimlyDatabase
    private var now = 0L
    private lateinit var retries: SyncRetries

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ZimlyDatabase::class.java).allowMainThreadQueries().build()
        db.remoteDao().insert(remote())
        retries = SyncRetries(db.syncRetryDao(), REMOTE_ID) { now }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `a sync that never failed has been failing for no time`() = runBlocking {
        assertEquals(Duration.ZERO, retries.failingFor())
    }

    @Test
    fun `the first failed run starts the failing time, and later ones keep it`() = runBlocking {
        now = 1_000
        retries.retrying("connection reset", nextTryAt = 61_000)
        now = 70_000
        retries.retrying("connection reset", nextTryAt = 190_000)
        now = 200_000

        assertEquals(199_000.milliseconds, retries.failingFor())
    }

    @Test
    fun `a waiting sync shows its last error and next try`() = runBlocking {
        retries.retrying("connection reset", nextTryAt = 61_000)

        assertEquals(NextTry("connection reset", 61_000), db.syncRetryDao().observe(REMOTE_ID).first()?.nextTry)
    }

    @Test
    fun `a run that starts hides the last error but keeps the failing time`() = runBlocking {
        now = 1_000
        retries.retrying("connection reset", nextTryAt = 61_000)
        now = 61_000

        retries.runStarted()

        assertNull(db.syncRetryDao().observe(REMOTE_ID).first()?.nextTry)
        assertEquals(60_000.milliseconds, retries.failingFor())
    }

    @Test
    fun `a sync that ended forgets its failures`() = runBlocking {
        retries.retrying("connection reset", nextTryAt = 61_000)

        retries.ended()

        assertNull(db.syncRetryDao().observe(REMOTE_ID).first())
        assertEquals(Duration.ZERO, retries.failingFor())
    }

    @Test
    fun `deleting the remote deletes its failures`() = runBlocking {
        retries.retrying("connection reset", nextTryAt = 61_000)

        db.remoteDao().deleteById(REMOTE_ID)

        assertNull(db.syncRetryDao().observe(REMOTE_ID).first())
    }

    private fun remote() = Remote(
        REMOTE_ID, "Photos", "https://s3.example.com", "key", "secret", "bucket", null,
        virtualHostedStyle = false, contentType = ContentType.MEDIA, contentUri = "Camera",
        direction = SyncDirection.UPLOAD,
    )

    private companion object {
        const val REMOTE_ID = 7
    }
}
