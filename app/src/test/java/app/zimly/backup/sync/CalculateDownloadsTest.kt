package app.zimly.backup.sync

import app.zimly.backup.data.media.ContentObject
import app.zimly.backup.data.s3.S3Object
import io.mockk.mockk
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

    /**
     * Keys like these name no file of their own: "x//photo.jpg" and "x/./photo.jpg" would land on
     * "x/photo.jpg", and ".." on nothing at all. They are skipped instead.
     */
    @Test
    fun `keys with empty, dot or dot-dot segments are skipped`() {
        val keys = listOf("x/photo.jpg", "x//photo.jpg", "x/./photo.jpg", "x/../photo.jpg", "..", ".", "/lead.jpg")

        val downloads = DownloadSyncService.calculateDownloads(keys.map { remote(it, 1) }, emptyList())

        assertEquals(listOf("x/photo.jpg"), downloads.map { it.name })
    }

    /**
     * Shared storage can't hold characters like ':' or '?' in a name; the storage provider stores
     * them as '_'. Matched by its stored name, such a file isn't downloaded again on every sync.
     */
    @Test
    fun `a key with characters the phone can't store matches its stored name`() {
        val remote = remote("notes/a:b?.txt", 1, modified = ZonedDateTime.now().minusDays(1))
        val local = local("notes/a_b_.txt", lastModified = ZonedDateTime.now())

        val downloads = DownloadSyncService.calculateDownloads(listOf(remote), listOf(local))

        assertEquals(emptyList<S3Object>(), downloads)
    }

    @Test
    fun `a key maps to the path the phone stores it under`() {
        assertEquals("a/b_c_d.txt", localPathOf("a/b:c?d.txt"))
        assertEquals("tab_name", localPathOf("tab\tname"))
        assertEquals(null, localPathOf("a//b"))
    }

    private fun remote(name: String, size: Long, modified: ZonedDateTime = ZonedDateTime.now()) =
        S3Object(name, size, "etag", modified)

    private fun local(relPath: String, lastModified: ZonedDateTime) = ContentObject(
        relPath, relPath, 1, "text/plain", mockk(), lastModified.toInstant().toEpochMilli(),
    )
}
