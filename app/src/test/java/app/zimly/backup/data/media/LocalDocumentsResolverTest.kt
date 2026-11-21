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
import kotlin.test.assertEquals
import kotlin.test.assertFails
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
        val result = resolver.createOrFindDocument("test.txt", "text/plain")

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
        val result = resolver.createOrFindDocument("Folder1/test.txt", "text/plain")

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

        val result = resolver.createOrFindDocument("Folder1/Folder2/test1.txt", "text/plain")

        assertTrue(result.authority == authority)
        assertTrue(result.path?.contains("Folder1/Folder2/test1.txt") == true)
    }

    @Test
    fun deleteRemovesTheDocumentAndKeepsItsFolder() {
        documentStore.add(folder1(), FakeDocument(TEST1_ID, "test1.txt", "text/plain", parentId = FOLDER1_ID))

        writeableResolver().delete("Folder1/test1.txt")

        assertNull(documentStore[TEST1_ID])
        assertNotNull(documentStore[FOLDER1_ID])
    }

    @Test
    fun deleteOfAMissingDocumentChangesNothing() {
        documentStore.add(folder1())

        writeableResolver().delete("Folder1/missing.txt")
        writeableResolver().delete("Missing/missing.txt")

        assertEquals(listOf(FOLDER1_ID), documentStore.list().map { it.id })
    }

    /**
     * The fake provider can't open files, so every open fails here. A document this call created
     * must not stay behind empty, with a timestamp that makes later syncs skip it.
     */
    @Test
    fun aDocumentCreatedForAFailedOpenIsRemoved() {
        documentStore.add(folder1())

        assertFails { writeableResolver().createOutputStream("Folder1/new.txt", "text/plain") }

        assertEquals(listOf(FOLDER1_ID), documentStore.list().map { it.id })
    }

    /** An existing document that failed to open was never written to, so it stays. */
    @Test
    fun anExistingDocumentThatFailsToOpenStays() {
        documentStore.add(folder1(), FakeDocument(TEST1_ID, "test1.txt", "text/plain", parentId = FOLDER1_ID))

        assertFails { writeableResolver().createOutputStream("Folder1/test1.txt", "text/plain") }

        assertNotNull(documentStore[TEST1_ID])
    }

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