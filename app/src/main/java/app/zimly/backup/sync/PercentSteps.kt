package app.zimly.backup.sync

/**
 * Turns a sync's progress (0 to 1) into a whole percent, and reports it only when it changes, so
 * the sync notification is updated at most 101 times however many progress values arrive.
 */
internal class PercentSteps {

    private var last: Int? = null

    /** The whole percent for [percentage], or null when it is the same as the last one reported. */
    fun next(percentage: Float): Int? {
        val percent = (percentage * 100).toInt().coerceIn(0, 100)
        if (percent == last) return null
        last = percent
        return percent
    }
}
