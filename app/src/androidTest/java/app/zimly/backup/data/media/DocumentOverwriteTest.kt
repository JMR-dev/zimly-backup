package app.zimly.backup.data.media

import android.Manifest
import android.app.UiAutomation
import android.content.Context
import android.provider.DocumentsContract
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [LocalDocumentsResolver] against the device's real external storage documents provider, which
 * is what a download sync writes to. A download that replaces a file with a shorter version must
 * leave exactly the new bytes. Opening a document in mode "w" didn't truncate it on the Android 17
 * emulator, so the end of the old file survived.
 *
 * A test can't pick a folder in the system picker, so it reaches the provider with the shell's
 * MANAGE_DOCUMENTS permission instead of a folder grant. That works on Android 17. Android 16
 * still refuses the provider ("obtain access using ACTION_OPEN_DOCUMENT"), so there the test
 * skips itself.
 */
@RunWith(AndroidJUnit4::class)
class DocumentOverwriteTest {

    private val uiAutomation: UiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val folder = "ZimlyOverwriteTest${System.currentTimeMillis()}"
    private val path = "$folder/$NAME"

    private lateinit var resolver: LocalDocumentsResolver

    @Before
    fun setUp() {
        uiAutomation.adoptShellPermissionIdentity(Manifest.permission.MANAGE_DOCUMENTS)
        assumeTrue("This Android version won't open the documents provider to a test; skipping.", providerReachable())
        resolver = LocalDocumentsResolver(context, DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT))
    }

    @After
    fun tearDown() {
        try {
            if (::resolver.isInitialized) deleteFolder()
        } finally {
            uiAutomation.dropShellPermissionIdentity()
        }
    }

    /**
     * The storage provider appends a type's extension to a name whose extension doesn't match the
     * type it's created with, and the next sync wouldn't find the file under its key. A download
     * creates documents with the type their name implies, so every name stays as it is.
     */
    @Test
    fun aDownloadKeepsEveryNameExactly() {
        val names = listOf("README", "notes.md", "data.unknownext", "photo.JPG")

        names.forEach { name -> resolver.openForWriting("$folder/$name").stream.use { it.write(1) } }

        val stored = resolver.listObjects().map { it.relPath }.filter { it.startsWith("$folder/") }
        assertEquals(names.map { "$folder/$it" }.sorted(), stored.sorted())
    }

    @Test
    fun overwritingWithAShorterFileLeavesOnlyTheNewBytes() {
        write(ByteArray(OLD_SIZE) { 'A'.code.toByte() })
        val shorter = ByteArray(NEW_SIZE) { 'B'.code.toByte() }

        write(shorter)

        assertArrayEquals(shorter, read())
    }

    /** Writes [bytes] the way a download sync does. */
    private fun write(bytes: ByteArray) {
        resolver.openForWriting(path).stream.use { it.write(bytes) }
    }

    private fun read(): ByteArray =
        resolver.getInputStream(resolver.createOrFindDocument(path)).use { it.readBytes() }

    private fun deleteFolder() {
        val treeUri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT)
        DocumentsContract.deleteDocument(context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(treeUri, "$ROOT/$folder"))
    }

    private fun providerReachable(): Boolean = try {
        context.contentResolver.acquireContentProviderClient(AUTHORITY)?.use { true } ?: false
    } catch (e: SecurityException) {
        Log.i(TAG, "The documents provider refused the test: ${e.message}")
        false
    }

    private companion object {
        const val TAG = "DocumentOverwriteTest"
        const val AUTHORITY = "com.android.externalstorage.documents"
        const val ROOT = "primary:DCIM"
        const val NAME = "file.bin"
        const val OLD_SIZE = 1000
        const val NEW_SIZE = 10
    }
}
