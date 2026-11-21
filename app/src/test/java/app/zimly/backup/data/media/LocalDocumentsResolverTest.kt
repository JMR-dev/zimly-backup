package app.zimly.backup.data.media

import android.content.Context
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import app.zimly.backup.data.media.FakeDocumentsProvider.FakeDocument
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * These tests rely on roboelectric to stub the [android.content.ContentProvider]. Additionally, tests
 * that need to create Documents need [FakeDocumentsRepository] to circumvent issues with security
 * restrictions in newer Android APIs and other problems.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalDocumentsResolverTest {

    private lateinit var documentStore: FakeDocumentStore
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val authority = "com.android.externalstorage.documents"

    @Before
    fun setup() {
        this.documentStore = FakeDocumentStore()
        val provider = FakeDocumentsProvider(authority, documentStore)
        ShadowContentResolver.registerProviderInternal(authority, provider)
    }

    @Test
    fun listObjectsRecursive() {
        // GIVEN
        documentStore.add(
            FakeDocument(
                "primary:Documents/test.txt",
                "test.txt",
                "text/plain",
                System.currentTimeMillis(),
                12345L,
                "primary:Documents"
            ),
            FakeDocument(
                "primary:Documents/Folder1",
                "Folder1",
                DocumentsContract.Document.MIME_TYPE_DIR,
                System.currentTimeMillis(),
                12345L,
                "primary:Documents"
            ),
            FakeDocument(
                "primary:Documents/Folder1/test1.txt",
                "test1.txt",
                "text/plain",
                System.currentTimeMillis(),
                12345L,
                "primary:Documents/Folder1"
            ),
        )
        val rootUri = DocumentsContract.buildTreeDocumentUri(authority, "primary:Documents")

        // Does not need FakeDocumentsRepository.
        val resolver = LocalDocumentsResolver(context, rootUri)

        // WHEN
        val result = resolver.listObjects()

        // THEN
        assertTrue(result.size == 2, "Lists all objects except directories")
        assertTrue(result.count { it.path == "Documents/Folder1/test1.txt" } == 1, "ContentObject.path starts with root")
        assertTrue(result.count { it.relPath == "Folder1/test1.txt" } == 1, "ContentObject.relPath does not contain root")
        assertTrue(result.count { it.path == "Documents/test.txt" } == 1, "ContentObject.path starts with root")
        assertTrue(result.count { it.relPath == "test.txt" } == 1, "ContentObject.relPath does not contain root")
    }

    @Test
    fun existingDocument() {
        // GIVEN
        documentStore.add(
            FakeDocument(
                "primary:Documents/test.txt",
                "test.txt",
                "text/plain",
                System.currentTimeMillis(),
                12345L,
                "primary:Documents"
            ),
            FakeDocument(
                "primary:Documents/Folder1",
                "Folder1",
                DocumentsContract.Document.MIME_TYPE_DIR,
                System.currentTimeMillis(),
                12345L,
                "primary:Documents"
            )
        )
        val rootUri = DocumentsContract.buildTreeDocumentUri(authority, "primary:Documents")

        // Does not need FakeDocumentsRepository.
        val resolver = LocalDocumentsResolver(context, rootUri)

        // WHEN
        val result = resolver.createOrFindDocument("test.txt")

        // THEN
        assertTrue(result.authority == authority)
        assertTrue(result.path?.contains("test.txt") == true)
    }

    @Test
    fun existingNestedDocument() {
        // GIVEN
        documentStore.add(
            FakeDocument(
                "primary:Documents/Folder1/test.txt",
                "test.txt",
                "text/plain",
                System.currentTimeMillis(),
                12345L,
                "primary:Documents/Folder1"
            ),
            FakeDocument(
                "primary:Documents/Folder1",
                "Folder1",
                DocumentsContract.Document.MIME_TYPE_DIR,
                System.currentTimeMillis(),
                12345L,
                "primary:Documents"
            )
        )
        val rootUri = DocumentsContract.buildTreeDocumentUri(authority, "primary:Documents")

        // Does not need FakeDocumentsRepository.
        val resolver = LocalDocumentsResolver(context, rootUri)

        // WHEN
        val result = resolver.createOrFindDocument("Folder1/test.txt")

        // THEN
        assertTrue(result.authority == authority)
        assertTrue(result.path?.contains("test.txt") == true)
    }

    @Test
    fun nonExistingParentAndDocument() {
        documentStore.add(
            FakeDocument(
                "primary:Documents/Folder1",
                "Folder1",
                DocumentsContract.Document.MIME_TYPE_DIR,
                System.currentTimeMillis(),
                12345L,
                "primary:Documents"
            )
        )

        val rootUri = DocumentsContract.buildTreeDocumentUri(authority, "primary:Documents")

        // Needs FakeDocumentsRepository.
        val resolver = LocalDocumentsResolver(
            context,
            rootUri,
            FakeDocumentsRepository(authority, documentStore)
        )

        val result = resolver.createOrFindDocument("Folder1/Folder2/test1.txt")

        assertTrue(result.authority == authority)
        assertTrue(result.path?.contains("Folder1/Folder2/test1.txt") == true)
    }

    @Test
    fun deleteRemovesTheDocumentAndKeepsItsFolder() {
        documentStore.add(folder1(), FakeDocument(TEST1_ID, "test1.txt", "text/plain", parentId = FOLDER1_ID))

        writeableResolver().delete(documentUri(TEST1_ID))

        assertNull(documentStore[TEST1_ID])
        assertNotNull(documentStore[FOLDER1_ID])
    }

    /** A delete goes by the document's own Uri, so one that's already gone is an error, not a no-op. */
    @Test
    fun deleteOfADocumentThatIsGoneFails() {
        documentStore.add(folder1())

        assertFailsWith<IOException> { writeableResolver().delete(documentUri("$FOLDER1_ID/missing.txt")) }
    }

    /**
     * The storage provider appends the type's own extension when a name's extension doesn't match
     * the type it's created with ("README" as text/plain becomes "README.txt"), so the next sync
     * wouldn't find it. A document is created with the type its name implies, which keeps the name.
     */
    @Test
    fun aNewDocumentIsCreatedWithTheTypeItsNameImplies() {
        documentStore.add(folder1())
        val resolver = writeableResolver()

        resolver.createOrFindDocument("Folder1/README")
        resolver.createOrFindDocument("Folder1/photo.jpg")

        assertEquals("application/octet-stream", documentStore["$FOLDER1_ID/README"]?.mimeType)
        assertEquals("image/jpeg", documentStore["$FOLDER1_ID/photo.jpg"]?.mimeType)
    }

    /**
     * A file where a folder belongs would make the provider create "Folder1 (1)" instead, and every
     * later sync another copy. The download fails with a message that says what's in the way.
     */
    @Test
    fun aFileWhereAFolderBelongsFailsTheDownloadAndStays() {
        documentStore.add(FakeDocument(FOLDER1_ID, "Folder1", "text/plain", size = 10, parentId = "primary:Documents"))

        val failure = assertFails { writeableResolver().createOrFindDocument("Folder1/test1.txt") }

        assertEquals("A file named 'Folder1' is where the folder 'Folder1' belongs. Move or rename it, then sync again.", failure.message)
        assertEquals(listOf(FOLDER1_ID), documentStore.list().map { it.id })
    }

    /**
     * The fake provider can't open files, so every open fails here. A document this call created
     * must not stay behind empty, with a timestamp that makes later syncs skip it.
     */
    @Test
    fun aDocumentCreatedForAFailedOpenIsRemoved() {
        documentStore.add(folder1())

        assertFails { writeableResolver().openForWriting("Folder1/new.txt") }

        assertEquals(listOf(FOLDER1_ID), documentStore.list().map { it.id })
    }

    /** An existing document that failed to open was never written to, so it stays. */
    @Test
    fun anExistingDocumentThatFailsToOpenStays() {
        documentStore.add(folder1(), FakeDocument(TEST1_ID, "test1.txt", "text/plain", parentId = FOLDER1_ID))

        assertFails { writeableResolver().openForWriting("Folder1/test1.txt") }

        assertNotNull(documentStore[TEST1_ID])
    }

    private fun documentUri(documentId: String) =
        DocumentsContract.buildDocumentUriUsingTree(DocumentsContract.buildTreeDocumentUri(authority, "primary:Documents"), documentId)

    // Needs FakeDocumentsRepository.
    private fun writeableResolver() = LocalDocumentsResolver(
        context,
        DocumentsContract.buildTreeDocumentUri(authority, "primary:Documents"),
        FakeDocumentsRepository(authority, documentStore)
    )

    private fun folder1() =
        FakeDocument(FOLDER1_ID, "Folder1", DocumentsContract.Document.MIME_TYPE_DIR, parentId = "primary:Documents")

    private companion object {
        const val FOLDER1_ID = "primary:Documents/Folder1"
        const val TEST1_ID = "primary:Documents/Folder1/test1.txt"
    }
}