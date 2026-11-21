package app.zimly.backup.data.s3

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.transformWhile
import kotlin.time.ComparableTimeMark
import kotlin.time.TimeSource


class ProgressTracker(private val size: Long) {

    /** What the transfer reported: some bytes moved, or it finished. */
    private sealed interface Step {
        data class Read(val bytes: Long, val timeMark: ComparableTimeMark) : Step
        data object Finished : Step
    }

    private val timeSource = TimeSource.Monotonic

    // An empty transfer is complete from the start: a download of it reads no bytes, so no step
    // would ever arrive to finish it.
    private val start = Progress(0, 0, percentageOf(0, size), size, null, timeSource.markNow())

    // Important: MutableStateFlow does not emit same values!
    private val progressFlow = MutableSharedFlow<Step>(500)
    // https://stackoverflow.com/questions/70935356/how-can-i-calculate-min-max-average-of-continuous-flow-in-kotlin
    // https://docs.oracle.com/javase/8/docs/api/java/util/DoubleSummaryStatistics.html

    fun stepBy(read: Long) {
        progressFlow.tryEmit(Step.Read(read, timeSource.markNow()))
    }

    /**
     * Ends [observe] once the transfer is done, even short of the size: the object shrank after
     * its size was read, or steps were dropped while the observer lagged. Unlike a step, this
     * waits for room in the buffer, so it is never dropped.
     */
    suspend fun finish() {
        progressFlow.emit(Step.Finished)
    }

    /** Completes at 100% or at [finish], whichever comes first. */
    fun observe(): Flow<Progress> {
        return progressFlow
            .transformWhile { step ->
                if (step is Step.Read) emit(step)
                step is Step.Read
            }
            .runningFold(start) { acc, step ->
                val readBytes = step.bytes
                val timeMark = step.timeMark
                val totalBytes = acc.totalReadBytes + readBytes

                val percentage = percentageOf(totalBytes, size)

                var duration = (timeMark - acc.timeMark).inWholeMilliseconds

                // Divide by zero safe guard
                duration = if (duration > 0) duration else 1

                // Bytes/ms to Bytes/s
                val bytesPerSec = readBytes * 1000 / duration
                Progress(readBytes, totalBytes, percentage, size, bytesPerSec, timeMark)
            }
            // takeWhile { it.percentage <= 1.0 } does not work ¯\_(ツ)_/¯
            .transformWhile {
                emit(it)
                it.percentage < 1.0
            }
    }
}

data class Progress(
    val readBytes: Long,
    val totalReadBytes: Long,
    val percentage: Float,
    val size: Long,
    val bytesPerSec: Long?,
    val timeMark: ComparableTimeMark
)
