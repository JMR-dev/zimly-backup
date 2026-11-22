package app.zimly.backup.data.s3

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.CoreMatchers.nullValue
import org.hamcrest.MatcherAssert
import org.junit.Test
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

class ProgressTrackerTest {

    /**
     * With parts uploading in parallel, two OkHttp threads report steps at once. A step that took
     * its running total first but was emitted second would make progress go backwards, and the
     * sync would add a negative byte count.
     */
    @Test
    fun stepsFromTwoThreadsNeverGoBackwards() = runBlocking<Unit> {
        val stepsPerThread = 20_000
        val tracker = ProgressTracker(2L * stepsPerThread)
        // UNDISPATCHED subscribes before the first step, so nothing is missed at the start.
        val observed = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { tracker.observe().toList() }

        List(2) { thread { repeat(stepsPerThread) { tracker.stepBy(1) } } }.forEach { it.join() }
        tracker.finish()

        val totals = withTimeout(10.seconds) { observed.await() }.map { it.totalReadBytes }
        MatcherAssert.assertThat(totals.zipWithNext().firstOrNull { (a, b) -> b < a }, `is`(nullValue()))
        MatcherAssert.assertThat(totals.last(), `is`(2L * stepsPerThread))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun name() = runTest {
        val totalSize = 5000L
        val tracker = ProgressTracker(totalSize)

        val res = mutableListOf<Progress>()

        // https://developer.android.com/kotlin/flow/test
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            tracker.observe()
                .onEach { println("$it") }
                .toList(res)
        }

        tracker.stepBy(1000L)
        tracker.stepBy(1000L)
        tracker.stepBy(1000L)
        tracker.stepBy(1000L)
        tracker.stepBy(1000L)

        MatcherAssert.assertThat(res.last().totalReadBytes, `is`(totalSize))
        MatcherAssert.assertThat(res.last().percentage, `is`(1F))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun emptySize() = runTest {
        val totalSize = 0L
        val tracker = ProgressTracker(totalSize)

        val res = mutableListOf<Progress>()

        // https://developer.android.com/kotlin/flow/test
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            tracker.observe()
                .onEach { println("$it") }
                .toList(res)
        }

        tracker.stepBy(0L)

        MatcherAssert.assertThat(res.last().totalReadBytes, `is`(totalSize))
        MatcherAssert.assertThat(res.last().percentage, `is`(1F))
    }

    /** A download of an empty object reads no bytes, so it never steps the tracker. */
    @Test
    fun emptySizeIsCompleteWithoutAStep() = runTest {
        val progress = withTimeout(5.seconds) { ProgressTracker(0L).observe().toList() }

        MatcherAssert.assertThat(progress.last().percentage, `is`(1F))
    }

    /**
     * A transfer can end short of the size it was given: the object shrank after its size was
     * read, or steps were dropped while the observer lagged. finish() still ends observing.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun finishEndsObservingShortOfTheSize() = runTest {
        val tracker = ProgressTracker(1000L)
        val progress = async(UnconfinedTestDispatcher(testScheduler)) { tracker.observe().toList() }

        tracker.stepBy(100L)
        tracker.finish()

        val observed = withTimeout(5.seconds) { progress.await() }
        MatcherAssert.assertThat(observed.last().totalReadBytes, `is`(100L))
    }

    /**
     * Steps beyond the buffer are dropped while the observer lags, but their bytes still count:
     * the sync adds up each progress's bytes, and would otherwise report success short of 100%.
     */
    @Test
    fun droppedStepsStillCountTheirBytes() = runTest {
        val steps = BUFFERED_STEPS + 100
        val tracker = ProgressTracker(steps * STEP_BYTES)
        val progress = async { tracker.observe().toList() }
        testScheduler.runCurrent() // The observer subscribes, then lags behind every step below.

        repeat(steps.toInt()) { tracker.stepBy(STEP_BYTES) }
        tracker.finish()

        val observed = withTimeout(5.seconds) { progress.await() }
        MatcherAssert.assertThat(observed.sumOf { it.readBytes }, `is`(steps * STEP_BYTES))
        MatcherAssert.assertThat(observed.last().percentage, `is`(1F))
    }

    /**
     * Past 2^24 bytes a Float can't tell the last few bytes apart: (size - 1) / size rounds to
     * 1.0f for a 64 MiB object. Observing must still go on until every byte is counted.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun observingEndsWithTheLastByteOfALargeObject() = runTest {
        val size = 64L * 1024 * 1024
        val tracker = ProgressTracker(size)
        val progress = async(UnconfinedTestDispatcher(testScheduler)) { tracker.observe().toList() }

        tracker.stepBy(size - 1)
        tracker.stepBy(1)

        val observed = withTimeout(5.seconds) { progress.await() }
        MatcherAssert.assertThat(observed.last().totalReadBytes, `is`(size))
    }

    private companion object {
        const val BUFFERED_STEPS = 500L
        const val STEP_BYTES = 10L
    }
}
