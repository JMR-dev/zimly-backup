package app.zimly.backup.sync

import app.zimly.backup.data.s3.S3Object
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZonedDateTime

class CalculateDownloadsTest {

    /**
     * Tools like the AWS console create empty "folder" objects whose keys end in '/'. Downloaded,
     * one became a file named like the folder, so the folder's real files went into "Camera (1)/",
     * then "Camera (2)/" on the next sync, and so on.
     */
    @Test
    fun `folder markers are not downloaded`() {
        val remotes = listOf(remote("Camera/", 0), remote("Camera/x.jpg", 1234))

        val downloads = DownloadSyncService.calculateDownloads(remotes, emptyList())

        assertEquals(listOf("Camera/x.jpg"), downloads.map { it.name })
    }

    private fun remote(name: String, size: Long) = S3Object(name, size, "etag", ZonedDateTime.now())
}
