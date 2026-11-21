package app.zimly.backup.sync

import android.net.Uri
import app.zimly.backup.data.media.ContentObject
import app.zimly.backup.data.s3.S3Object
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZonedDateTime

/**
 * Before 4c306ed, a folder marker such as "Camera/" was downloaded as an empty file named
 * "Camera", which keeps the real folder from being created: the provider makes "Camera (1)"
 * instead, and every sync downloads the folder's files again. Such a file is removed before a
 * download, but only when it is empty and its marker is still in the bucket.
 */
class StrayFolderFileTest {

    private val strayUri: Uri = mockk()

    @Test
    fun `an empty file named like a folder marker in the bucket is stray`() {
        val remotes = listOf(remote("Camera/"), remote("Camera/x.jpg"), remote("a/b/"))
        val locals = listOf(local("Camera", size = 0), local("a/b", size = 0))

        assertEquals(listOf("Camera", "a/b"), DownloadSyncService.strayMarkerFiles(remotes, locals).map { it.relPath })
    }

    /** A file with data in it is the user's, whatever the bucket says. */
    @Test
    fun `a file with data in it is never stray`() {
        val remotes = listOf(remote("Camera/"), remote("Camera/x.jpg"))

        assertEquals(emptyList<ContentObject>(), DownloadSyncService.strayMarkerFiles(remotes, listOf(local("Camera", size = 10))))
    }

    @Test
    fun `an empty file without a marker in the bucket is not stray`() {
        val remotes = listOf(remote("Camera/x.jpg"))

        assertEquals(emptyList<ContentObject>(), DownloadSyncService.strayMarkerFiles(remotes, listOf(local("Camera", size = 0))))
    }

    private fun remote(name: String) = S3Object(name, 0, "etag", ZonedDateTime.now())

    private fun local(relPath: String, size: Long) =
        ContentObject(relPath, relPath, size, "application/octet-stream", strayUri, System.currentTimeMillis())
}
