package app.zimly.backup.sync

/** [DownloadsInProgress] in memory, as the tests' stand-in for the database table. */
class FakeDownloadsInProgress(vararg recorded: String) : DownloadsInProgress {

    private val keys = recorded.toMutableSet()

    override fun keys(): Set<String> = keys.toSet()

    override suspend fun started(key: String) {
        keys += key
    }

    override suspend fun finished(key: String) {
        keys -= key
    }
}
