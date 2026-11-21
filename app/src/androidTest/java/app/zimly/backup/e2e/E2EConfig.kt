package app.zimly.backup.e2e

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Where a host script puts the end-to-end test's settings on the device. */
internal const val CONFIG_PATH = "/data/local/tmp/zimly-e2e.json"

/**
 * The end-to-end test's settings, written to the device by a host script. The endpoint, region,
 * bucket and credentials are required; the rest default to a quick run.
 */
internal data class E2EConfig(
    val endpoint: String,
    val region: String,
    val bucket: String,
    val key: String,
    val secret: String,
    /** Size of the seeded video. The default is over the 5 MiB part size, so it goes up in parts. */
    val videoBytes: Long = DEFAULT_VIDEO_BYTES,
    val syncTimeout: Duration = DEFAULT_SYNC_TIMEOUT,
    /** The sync must take at least this long, so a long-upload run proves its throttling worked. */
    val minSync: Duration = Duration.ZERO,
    /** The sync must show its foreground notification. Off for a control run on code without it. */
    val expectForeground: Boolean = true,
) {
    init {
        require(videoBytes > 0) { "videoBytes must be positive" }
        require(minSync < syncTimeout) { "minSyncMinutes must be below syncTimeoutMinutes" }
    }

    companion object {
        const val DEFAULT_VIDEO_BYTES = 6L * 1024 * 1024 + 321
        val DEFAULT_SYNC_TIMEOUT = 10.minutes

        /** Reads the settings as the shell user and deletes the file at once. Null if it isn't there. */
        fun readAndDelete(): E2EConfig? {
            val json = shell("cat $CONFIG_PATH").trim()
            shell("rm -f $CONFIG_PATH")
            return if (json.isEmpty()) null else parse(json)
        }

        fun parse(json: String): E2EConfig {
            val parsed = JSONObject(json)
            return E2EConfig(
                endpoint = parsed.getString("endpoint"),
                region = parsed.getString("region"),
                bucket = parsed.getString("bucket"),
                key = parsed.getString("key"),
                secret = parsed.getString("secret"),
                videoBytes = parsed.optLong("videoBytes", DEFAULT_VIDEO_BYTES),
                syncTimeout = parsed.optLong("syncTimeoutMinutes", DEFAULT_SYNC_TIMEOUT.inWholeMinutes).minutes,
                minSync = parsed.optLong("minSyncMinutes", 0).minutes,
                expectForeground = parsed.optBoolean("expectForeground", true),
            )
        }

        /** Apps can't read /data/local/tmp, so the file is read through the shell user. */
        private fun shell(command: String): String {
            val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            return ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes().decodeToString() }
        }
    }
}
