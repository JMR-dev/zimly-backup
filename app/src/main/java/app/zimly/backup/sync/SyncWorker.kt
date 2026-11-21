package app.zimly.backup.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.zimly.backup.data.db.ZimlyDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.io.IOException
import java.time.Duration

class SyncWorker(
    private val context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters) {

    override suspend fun doWork(): Result {

        val syncService = try {
            initSyncService(context, inputData)
        } catch (e: Exception) {
            return Result.failure(
                Data.Builder()
                    .putString(SyncOutputs.ERROR, "Failed to initiate sync: ${e.message}")
                    .build()
            )
        }

        Log.i(TAG, "Launching sync...")

        val foreground = ForegroundProgress()
        foreground.start()

        return syncService.synchronize()
            .onEach {
                foreground.update(it.percentage)
                setProgressAsync(
                    Data.Builder()
                        .putInt(SyncOutputs.PROGRESS_COUNT, it.transferredFiles)
                        .putLong(SyncOutputs.PROGRESS_BYTES, it.transferredBytes)
                        .putIfNotNull(SyncOutputs.PROGRESS_BYTES_PER_SEC, it.bytesPerSecond)
                        .putFloat(SyncOutputs.PROGRESS_PERCENTAGE, it.percentage)
                        .putInt(SyncOutputs.DIFF_COUNT, it.totalFiles)
                        .putLong(SyncOutputs.DIFF_BYTES, it.totalBytes)
                        .build()
                )
            }
            .map {
                Result.success(
                    Data.Builder()
                        .putInt(SyncOutputs.PROGRESS_COUNT, it.transferredFiles)
                        .putLong(SyncOutputs.PROGRESS_BYTES, it.transferredBytes)
                        .putIfNotNull(SyncOutputs.PROGRESS_BYTES_PER_SEC, it.bytesPerSecond)
                        .putFloat(SyncOutputs.PROGRESS_PERCENTAGE, it.percentage)
                        .putInt(SyncOutputs.DIFF_COUNT, it.totalFiles)
                        .putLong(SyncOutputs.DIFF_BYTES, it.totalBytes)
                        .build()
                )
            }
            .catch {
                Log.e(TAG, "Failed to sync diff.", it)

                emit(onError(it))
            }
            .last()
    }

    /**
     * Runs this sync as a foreground job, which lifts Android's 10-minute limit on background work,
     * and keeps its notification's progress current. Android 12+ refuses a foreground start while
     * the app is in the background (a retry, for example): the sync then runs on as an ordinary
     * job, under that limit, and this stops asking.
     */
    private inner class ForegroundProgress {
        private val steps = PercentSteps()
        private val cancel by lazy {
            WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        }
        private var allowed = true

        suspend fun start() = show(percent = null)

        suspend fun update(percentage: Float) {
            if (!allowed) return
            steps.next(percentage)?.let { show(it) }
        }

        private suspend fun show(percent: Int?) {
            val info = SyncNotification.foregroundInfo(
                applicationContext, remoteIdOf(inputData), percent, cancel
            )
            allowed = try {
                setForeground(info)
                true
            } catch (e: CancellationException) {
                throw e // A CancellationException is an IllegalStateException: don't treat it as a refusal.
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Sync runs without a foreground notification, under the 10-minute limit", e)
                false
            }
        }
    }

    /**
     * Fail or retry depending on the thrown error.
     */
    private fun onError(it: Throwable): Result {
        val result = when (it) {
            is IOException -> Result.retry() // Network errors → retry
            else -> Result.failure(
                Data.Builder()
                    .putString(SyncOutputs.ERROR, it.message)
                    .build()
            )
        }
        return result
    }

    companion object {
        val TAG: String? = SyncWorker::class.simpleName

        /**
         * The work request that syncs [remoteId]: it waits for a network connection and retries
         * with exponential backoff.
         */
        fun request(remoteId: Int): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(1))
                .setInputData(workDataOf(SyncInputs.REMOTE_CONFIGURATION_ID to remoteId))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()

        /** The remote to sync, or -1 when the input names none. */
        private fun remoteIdOf(inputData: Data): Int =
            inputData.getInt(SyncInputs.REMOTE_CONFIGURATION_ID, -1)

        // TODO: provider passed through constructor?
        suspend fun initSyncService(context: Context, inputData: Data): SyncService {

            val db = ZimlyDatabase.getInstance(context.applicationContext)
            val dao = db.remoteDao()

            val remoteId = remoteIdOf(inputData)
            if (remoteId == -1) {
                throw IllegalArgumentException("Remote configuration cannot be loaded, no ID passed.")
            }
            val remote = dao.loadById(remoteId)

            return SyncService.get(context, remote)
        }
    }
}

object SyncInputs {
    const val REMOTE_CONFIGURATION_ID = "remote_id"
}

object SyncOutputs {
    const val PROGRESS_COUNT = "progress_count"
    const val PROGRESS_BYTES = "progress_bytes"
    const val PROGRESS_BYTES_PER_SEC = "progress_bytes_per_sec"
    const val PROGRESS_PERCENTAGE = "progress_percentage"
    const val DIFF_COUNT = "diff_count"
    const val DIFF_BYTES = "diff_bytes"
    const val ERROR = "error"
}
