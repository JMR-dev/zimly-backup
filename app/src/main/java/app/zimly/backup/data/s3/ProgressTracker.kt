package app.zimly.backup.data.s3

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.transformWhile
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.ComparableTimeMark
import kotlin.time.TimeSource


class ProgressTracker(private val size: Long) {

    /**
     * What the transfer reported, with the bytes moved so far: some bytes moved, or it finished.
     * Each step carries the running total, so a step dropped while the observer lags loses no
     * bytes: the next one that arrives includes them.
     */
    private sealed interface Step {
        val totalBytes: Long
        val timeMark: ComparableTimeMark

        data class Read(override val totalBytes: Long, override val timeMark: ComparableTimeMark) : Step
        data class Finished(override val totalBytes: Long, override val timeMark: ComparableTimeMark) : Step
    }

    private val timeSource = TimeSource.Monotonic

    private val transferred = AtomicLong(0)

    // An empty transfer is complete from the start: a download of it reads no bytes, so no step
    // would ever arrive to finish it.
    private val start = Progress(0, 0, percentageOf(0, size), size, null, timeSource.markNow())

    // Important: MutableStateFlow does not emit same values!
    private val progressFlow = MutableSharedFlow<Step>(500)
    // https://stackoverflow.com/questions/70935356/how-can-i-calculate-min-max-average-of-continuous-flow-in-kotlin
    // https://docs.oracle.com/javase/8/docs/api/java/util/DoubleSummaryStatistics.html

    fun stepBy(read: Long) {
        progressFlow.tryEmit(Step.Read(transferred.addAndGet(read), timeSource.markNow()))
    }

    /**
     * Ends [observe] once the transfer is done, with the bytes of any steps dropped on the way,
     * even short of the size (the object shrank after its size was read). Unlike a step, this
     * waits for room in the buffer, so it is never dropped.
     */
    suspend fun finish() {
        progressFlow.emit(Step.Finished(transferred.get(), timeSource.markNow()))
    }

    /** Completes once every byte of the size is counted, or at [finish], whichever comes first. */
    fun observe(): Flow<Progress> {
        return progressFlow
            .transformWhile { step ->
                emit(step)
                step is Step.Read
            }
            .runningFold(start) { acc, step -> progressAt(acc, step) }
            // Ends on the bytes, not the Float percentage: past 2^24 bytes, a total a few bytes
            // short of the size already rounds to 100%.
            .transformWhile {
                emit(it)
                it.totalReadBytes < size
            }
    }

    /**
     * The progress at [step], after [acc]. A step that adds no bytes (a finish with nothing
     * dropped) keeps the last speed instead of reporting zero.
     */
    private fun progressAt(acc: Progress, step: Step): Progress {
        val readBytes = step.totalBytes - acc.totalReadBytes
        // At least 1 ms, so the division below is safe.
        val duration = (step.timeMark - acc.timeMark).inWholeMilliseconds.coerceAtLeast(1)
        // Bytes/ms to Bytes/s
        val bytesPerSec = if (readBytes > 0) readBytes * 1000 / duration else acc.bytesPerSec
        val percentage = percentageOf(step.totalBytes, size)
        return Progress(readBytes, step.totalBytes, percentage, size, bytesPerSec, step.timeMark)
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
