package app.zimly.backup.data.media

import android.net.Uri
import android.provider.DocumentsContract
import app.zimly.backup.data.media.FakeDocumentsProvider.FakeDocument
import java.io.FileNotFoundException
import java.io.OutputStream

class FakeDocumentsRepository(
    private val authority: String,
    private val documentStore: FakeDocumentStore
) : DocumentsRepository {

    /**
     * Stub implementation to make [DocumentsContract.createDocument] work.
     */
    override fun createDocument(
        parentDocumentUri: Uri,
        mimeType: String,
        displayName: String
    ): Uri? {
        val parentDocId = DocumentsContract.getDocumentId(parentDocumentUri)

        val newDocId = "$parentDocId/$displayName"

        val newDocUri =
            DocumentsContract.buildDocumentUri(authority, newDocId)

        documentStore.add(
            FakeDocument(
                id = newDocId,
                parentId = parentDocId,
                name = displayName,
                mimeType = mimeType,
            )
        )

        return newDocUri
    }

    /** Stub implementation to make [DocumentsContract.deleteDocument] work. */
    override fun deleteDocument(documentUri: Uri): Boolean =
        documentStore.remove(DocumentsContract.getDocumentId(documentUri)) != null

    /** The fake stores no file contents, so every open fails, as a provider's can. */
    override fun openOutputStream(documentUri: Uri): OutputStream? =
        throw FileNotFoundException("The fake stores no file contents: $documentUri")
}