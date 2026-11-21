package app.zimly.backup.data.media

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.OutputStream

/**
 * Wraps calls to the untestable [DocumentsContract].
 */
class DocumentsContractRepository(private val contentResolver: ContentResolver) : DocumentsRepository {

    override fun createDocument(parentDocumentUri: Uri, mimeType: String, displayName: String): Uri? {
        return DocumentsContract.createDocument(
            contentResolver,
            parentDocumentUri,
            mimeType,
            displayName
        )
    }

    override fun deleteDocument(documentUri: Uri): Boolean =
        DocumentsContract.deleteDocument(contentResolver, documentUri)

    /**
     * Mode "wt": write, truncating what was there. Plain "w" (openOutputStream's default) doesn't
     * truncate with the external storage provider (seen on the Android 17 emulator), so a shorter
     * new version would keep the old file's end.
     */
    override fun openOutputStream(documentUri: Uri): OutputStream? =
        contentResolver.openOutputStream(documentUri, "wt")
}