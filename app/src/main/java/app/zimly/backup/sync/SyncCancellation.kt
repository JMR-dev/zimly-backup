package app.zimly.backup.sync

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.WorkManager
import app.zimly.backup.data.db.ZimlyDatabase
import app.zimly.backup.data.db.retry.SyncRetryDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Cancels a remote's sync, whether it is running or waiting to retry: the sync screen's Cancel
 * button and the notifications' Cancel action both come here. Besides cancelling the work, it
 * forgets the sync's failures and removes its retry notification: no run is left to do that.
 */
internal class SyncCancellation(
    private val context: Context,
    private val workManager: WorkManager = WorkManager.getInstance(context),
    private val retryDao: SyncRetryDao = ZimlyDatabase.getInstance(context).syncRetryDao(),
) {

    suspend fun cancel(remoteId: Int) {
        workManager.cancelUniqueWork(SyncWorker.uniqueWorkName(remoteId))
        retryDao.delete(remoteId)
        RetryNotification.dismiss(context, remoteId)
    }

    companion object {
        const val EXTRA_REMOTE_ID = "remote_id"

        /** The Cancel action of [remoteId]'s notifications. */
        fun pendingIntent(context: Context, remoteId: Int): PendingIntent {
            val intent = Intent(context, CancelSyncReceiver::class.java).putExtra(EXTRA_REMOTE_ID, remoteId)
            return PendingIntent.getBroadcast(
                context, remoteId, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
    }
}

/** Receives a notification's Cancel action. Not exported: only the app's own intents reach it. */
class CancelSyncReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val remoteId = intent.getIntExtra(SyncCancellation.EXTRA_REMOTE_ID, NO_REMOTE)
        if (remoteId == NO_REMOTE) {
            Log.w(TAG, "A cancel without a remote id: nothing to cancel")
            return
        }
        // goAsync keeps the process alive for the database write after onReceive returns.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SyncCancellation(context.applicationContext).cancel(remoteId)
            } catch (e: Exception) {
                // No screen to show it on: the log is where it goes.
                Log.e(TAG, "Failed to cancel the sync of remote $remoteId", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val TAG: String? = CancelSyncReceiver::class.simpleName
        const val NO_REMOTE = -1
    }
}
