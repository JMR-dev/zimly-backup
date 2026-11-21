package app.zimly.backup.permission

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class NotificationPermissionTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    @Test
    @Config(sdk = [35])
    fun `Android 13+ asks for the permission while it isn't granted`() {
        assertEquals(Manifest.permission.POST_NOTIFICATIONS, missingNotificationPermission(application))
    }

    @Test
    @Config(sdk = [35])
    fun `nothing to ask once it is granted`() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertNull(missingNotificationPermission(application))
    }

    @Test
    @Config(sdk = [32])
    fun `Android 12 and older have no notification permission to ask for`() {
        assertNull(missingNotificationPermission(application))
    }
}
