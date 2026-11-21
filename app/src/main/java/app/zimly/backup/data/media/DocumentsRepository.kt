package app.zimly.backup.data.media

import android.net.Uri
import java.io.OutputStream

/**
 * Wraps tricky APIs related to [android.content.ContentProvider] and [android.provider.DocumentsContract]
 * that make testing a PITA.
 */
interface DocumentsRepository {

    fun createDocument(parentDocumentUri: Uri, mimeType: String, displayName: String): Uri?

    /** True when the document was deleted. */
    fun deleteDocument(documentUri: Uri): Boolean

    /** Opens the document for writing, replacing what it held. Null if the provider can't. */
    fun openOutputStream(documentUri: Uri): OutputStream?
}