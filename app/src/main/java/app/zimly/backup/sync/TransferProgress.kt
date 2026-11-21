package app.zimly.backup.sync

import app.zimly.backup.data.s3.Progress
import app.zimly.backup.data.s3.percentageOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.runningFold

/** What happened during one file's transfer: some bytes moved, or the transfer finished. */
private sealed interface TransferEvent {
    data class Moved(val progress: Progress) : TransferEvent
    data object Finished : TransferEvent
}

/**
 * Runs each file's transfer in turn and folds them into [SyncProgress], starting from the totals.
 *
 * A file counts as transferred only once its transfer completes without error, so a file still
 * in flight, or one that failed, is never counted. A failure ends the flow with that error.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<Flow<Progress>>.toSyncProgress(totalFiles: Int, totalBytes: Long): Flow<SyncProgress> {
    val start = SyncProgress.EMPTY.copy(totalFiles = totalFiles, totalBytes = totalBytes)
    return flatMapConcat { transfer -> transfer.asEvents() }
        .runningFold(start) { progress, event -> progress.after(event) }
}

private fun Flow<Progress>.asEvents(): Flow<TransferEvent> =
    map<Progress, TransferEvent> { TransferEvent.Moved(it) }
        .onCompletion { failure -> if (failure == null) emit(TransferEvent.Finished) }

private fun SyncProgress.after(event: TransferEvent): SyncProgress = when (event) {
    is TransferEvent.Moved -> {
        val bytes = transferredBytes + event.progress.readBytes
        copy(
            transferredBytes = bytes,
            percentage = percentageOf(bytes, totalBytes),
            bytesPerSecond = event.progress.bytesPerSec,
        )
    }
    TransferEvent.Finished -> copy(transferredFiles = transferredFiles + 1)
}
