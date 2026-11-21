package app.zimly.backup.data.media

import android.content.ContentResolver
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Mode "w" doesn't truncate with the real external storage provider, so a shorter download kept
 * the old file's end. DocumentOverwriteTest shows that on a device, but skips itself on the
 * Android 16 image CI's emulator job uses. This check runs with the unit tests everywhere.
 */
class DocumentsContractRepositoryTest {

    @Test
    fun `a document opens for writing in truncate mode`() {
        val document: Uri = mockk()
        val contentResolver: ContentResolver = mockk()
        every { contentResolver.openOutputStream(document, any()) } returns ByteArrayOutputStream()

        DocumentsContractRepository(contentResolver).openOutputStream(document)

        verify(exactly = 1) { contentResolver.openOutputStream(document, "wt") }
    }
}
