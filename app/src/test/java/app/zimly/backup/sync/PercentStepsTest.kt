package app.zimly.backup.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class PercentStepsTest {

    @Test
    fun `the first value is always reported`() {
        assertEquals(0, PercentSteps().next(0f))
    }

    @Test
    fun `a percent is reported only when the whole percent changes`() {
        val steps = PercentSteps()

        val reported = listOf(0f, 0.004f, 0.01f, 0.019f, 0.5f, 1f).map { steps.next(it) }

        assertEquals(listOf(0, null, 1, null, 50, 100), reported)
    }

    @Test
    fun `values outside 0 to 1 are clamped`() {
        val steps = PercentSteps()

        assertEquals(100, steps.next(1.5f))
        assertEquals(0, steps.next(-0.5f))
    }
}
