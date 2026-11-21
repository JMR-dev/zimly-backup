package app.zimly.backup.data.db.retry

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncRetryDao {

    @Query("SELECT * FROM sync_retry WHERE remote_id = :remoteId")
    fun observe(remoteId: Int): Flow<SyncRetry?>

    @Query("SELECT * FROM sync_retry WHERE remote_id = :remoteId")
    suspend fun load(remoteId: Int): SyncRetry?

    /**
     * Records a failed run that will be tried again: [failedAt] becomes the first failure unless
     * an earlier one is already recorded. One transaction, so the two steps can't half-apply.
     */
    @Transaction
    suspend fun recordRetry(remoteId: Int, failedAt: Long, nextTry: NextTry) {
        insertFirstFailure(remoteId, failedAt)
        setNextTry(remoteId, nextTry.lastError, nextTry.at)
    }

    @Query("INSERT OR IGNORE INTO sync_retry (remote_id, first_failed_at) VALUES (:remoteId, :failedAt)")
    suspend fun insertFirstFailure(remoteId: Int, failedAt: Long)

    @Query("UPDATE sync_retry SET last_error = :lastError, next_try_at = :at WHERE remote_id = :remoteId")
    suspend fun setNextTry(remoteId: Int, lastError: String, at: Long)

    @Query("UPDATE sync_retry SET last_error = NULL, next_try_at = NULL WHERE remote_id = :remoteId")
    suspend fun clearNextTry(remoteId: Int)

    @Query("DELETE FROM sync_retry WHERE remote_id = :remoteId")
    suspend fun delete(remoteId: Int)
}
