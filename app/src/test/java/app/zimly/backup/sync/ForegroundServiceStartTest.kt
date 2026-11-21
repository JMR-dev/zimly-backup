package app.zimly.backup.sync

import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
import android.os.Build.VERSION_CODES.R
import android.os.Build.VERSION_CODES.S
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android 12+ refuses to start a foreground service while the app is in the background, unless
 * it is exempt, and WorkManager doesn't undo its bookkeeping after that refusal, so the sync only
 * asks when Android will allow it.
 */
class ForegroundServiceStartTest {

    @Test
    fun `before Android 12 a foreground service may always start`() {
        assertTrue(SyncWorker.mayStartForegroundService(R, IMPORTANCE_CACHED, ignoresBatteryOptimizations = false))
    }

    @Test
    fun `from Android 12 it may start while the app is in the foreground`() {
        assertTrue(SyncWorker.mayStartForegroundService(S, IMPORTANCE_FOREGROUND, ignoresBatteryOptimizations = false))
    }

    @Test
    fun `from Android 12 it may not start from the background`() {
        assertFalse(SyncWorker.mayStartForegroundService(S, IMPORTANCE_CACHED, ignoresBatteryOptimizations = false))
        assertFalse(SyncWorker.mayStartForegroundService(S, IMPORTANCE_FOREGROUND_SERVICE, ignoresBatteryOptimizations = false))
    }

    /** Android exempts an app the user took off battery optimization; the sync screen suggests it. */
    @Test
    fun `from Android 12 it may start from the background without battery optimization`() {
        assertTrue(SyncWorker.mayStartForegroundService(S, IMPORTANCE_CACHED, ignoresBatteryOptimizations = true))
    }
}
