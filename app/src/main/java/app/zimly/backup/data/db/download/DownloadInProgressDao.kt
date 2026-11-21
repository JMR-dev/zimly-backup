package app.zimly.backup.data.db.download

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DownloadInProgressDao {

    /** Blocking: the diff that reads it runs in blocking code, off the main thread. */
    @Query("SELECT object_key FROM download_in_progress WHERE remote_id = :remoteId")
    fun keys(remoteId: Int): List<String>

    /** Recording a key that is already recorded changes nothing. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(download: DownloadInProgress)

    @Query("DELETE FROM download_in_progress WHERE remote_id = :remoteId AND object_key = :key")
    suspend fun delete(remoteId: Int, key: String)
}
