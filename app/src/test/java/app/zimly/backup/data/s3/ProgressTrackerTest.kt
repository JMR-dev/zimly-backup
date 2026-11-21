package app.zimly.backup.data.s3

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.MatcherAssert
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class ProgressTrackerTest {

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

}
