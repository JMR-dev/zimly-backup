package app.zimly.backup.sync

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.zimly.backup.data.db.ZimlyDatabase
import app.zimly.backup.data.db.retry.NextTry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlin.time.Duration

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

        val retries = SyncRetries(ZimlyDatabase.getInstance(applicationContext).syncRetryDao(), remoteId)
        retries.runStarted()
        RetryNotification.dismiss(applicationContext, remoteId)

        Log.i(TAG, "Launching sync...")

        val foreground = ForegroundProgress()
        foreground.start()
        var finishedAFile = false

        val result = syncService.synchronize()
            .onEach {
                if (it.transferredFiles > 0) finishedAFile = true
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

                emit(onError(it, retries, finishedAFile))
            }
            .last()
        // A sync that succeeded or failed for good is over; one waiting to retry isn't.
        if (!result.isRetry()) retries.ended()
        return result
    }

    private val remoteId: Int
        get() = remoteIdOf(inputData)

    /**
     * Fails or retries depending on the error and on how long the sync has been failing, and
     * says so in a notification: until the next run, nothing else shows it. A run that
     * [finishedAFile] made progress, so the failing time starts again.
     */
    private suspend fun onError(error: Throwable, retries: SyncRetries, finishedAFile: Boolean): Result {
        if (finishedAFile) retries.progressMade()
        val failingFor = retries.failingFor()
        val result = syncResultOf(error, failingFor)
        if (result.isRetry()) notifyRetrying(error, retries, firstFailure = failingFor == Duration.ZERO) else notifyFailed(result)
        return result
    }

    /** Records the retry and posts its notification, which alerts only for a streak's [firstFailure]. */
    private suspend fun notifyRetrying(error: Throwable, retries: SyncRetries, firstFailure: Boolean) {
        val message = error.message ?: error.javaClass.simpleName
        val nextTry = NextTry(message, nextTryAt(System.currentTimeMillis(), runAttemptCount))
        retries.retrying(nextTry.lastError, nextTry.at)
        val cancel = SyncCancellation.pendingIntent(applicationContext, remoteId)
        val notification = RetryNotification.retrying(applicationContext, nextTry, cancel, alert = firstFailure)
        RetryNotification.post(applicationContext, remoteId, notification)
    }

    private fun notifyFailed(result: Result) {
        val error = result.outputData.getString(SyncOutputs.ERROR) ?: UNKNOWN_ERROR
        RetryNotification.post(applicationContext, remoteId, RetryNotification.failed(applicationContext, error))
    }

    /**
     * Runs this sync as a foreground job, which lifts Android's runtime limit on background jobs
     * (10 to 30 minutes), and keeps its notification's progress current.
     *
     * It asks only when [mayStartForegroundService]: WorkManager moves the work into its foreground
     * bookkeeping before Android refuses a start, and doesn't undo that, so the work would then
     * ignore Android's stop requests. A sync that starts in the background (a retry, for example)
     * runs as an ordinary job, and moves to the foreground at the next progress step after the
     * user opens the app. Until then, a file that needs longer than the limit restarts on every
     * run; resuming uploads across runs is what fixes that.
     */
    private inner class ForegroundProgress {
        private val steps = PercentSteps()
        // The same Cancel as the retry notification's, which also forgets the sync's failures.
        private val cancel by lazy { SyncCancellation.pendingIntent(applicationContext, remoteId) }
        private var inForeground = false

        suspend fun start() {
            if (mayStartForegroundService(applicationContext)) {
                show(percent = null)
            } else {
                Log.i(TAG, "The app is in the background: the sync runs as an ordinary job for now")
            }
        }

        /** Updates the notification once per whole percent, moving to the foreground if it now can. */
        suspend fun update(percentage: Float) {
            val percent = steps.next(percentage) ?: return
            if (inForeground || mayStartForegroundService(applicationContext)) show(percent)
        }

        private suspend fun show(percent: Int?) {
            val info = SyncNotification.foregroundInfo(applicationContext, remoteId, percent, cancel)
            inForeground = try {
                setForeground(info)
                true
            } catch (e: CancellationException) {
                throw e // A CancellationException is an IllegalStateException: don't treat it as a refusal.
            } catch (e: IllegalStateException) {
                // A race between the check and the start (the app left the screen in between).
                // WorkManager still counts the work as foreground (see above), so Android's stops
                // are ignored until the sync ends. A later request, once the app is back, restores
                // the notification and the foreground service.
                Log.w(TAG, "Android refused the foreground start; asking again at the next progress step", e)
                false
            }
        }
    }

    companion object {
        val TAG: String? = SyncWorker::class.simpleName
        private const val UNKNOWN_ERROR = "Unknown error"

        /** The unique work name of [remoteId]'s sync, which keeps each remote to one sync at a time. */
        fun uniqueWorkName(remoteId: Int): String = "sync_$remoteId"

        /**
         * The work request that syncs [remoteId]: it waits for a network connection and retries
         * with exponential backoff.
         */
        fun request(remoteId: Int): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, SYNC_BACKOFF)
                .setInputData(workDataOf(SyncInputs.REMOTE_CONFIGURATION_ID to remoteId))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()

        /** Whether this process may start a foreground service now (see [mayStartForegroundService]). */
        internal fun mayStartForegroundService(context: Context): Boolean =
            mayStartForegroundService(Build.VERSION.SDK_INT, currentImportance(), ignoresBatteryOptimizations(context))

        /**
         * Android 12+ allows a foreground service start from an app in the foreground (the user just
         * tapped Sync), or from one the user took off battery optimization, which the sync screen
         * suggests. Earlier versions always allow it.
         */
        internal fun mayStartForegroundService(sdkInt: Int, importance: Int, ignoresBatteryOptimizations: Boolean): Boolean =
            sdkInt < Build.VERSION_CODES.S ||
                importance <= RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                ignoresBatteryOptimizations

        private fun currentImportance(): Int =
            RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance

        private fun ignoresBatteryOptimizations(context: Context): Boolean =
            context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

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
