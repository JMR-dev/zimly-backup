package app.zimly.backup.data.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * https://developer.android.com/training/data-storage/shared/documents-files#grant-access-directory
 * https://developer.android.com/training/data-storage/shared/documents-files#persist-permissions
 */
class LocalDocumentsResolver(context: Context, private val root: Uri, val documentsRepository: DocumentsRepository = DocumentsContractRepository(context.contentResolver)) :
    LocalContentResolver, WriteableContentResolver {

    val contentResolver: ContentResolver = context.contentResolver
    val rootDocumentId: String = DocumentsContract.getTreeDocumentId(root)

    companion object {
        private val TAG: String? = LocalDocumentsResolver::class.simpleName
        private const val OCTET_STREAM = "application/octet-stream"

        /**
         * Strips off the storage type path from the documentId, e.g. "primary:some/path" -> "some/path".
         */
        private fun removeStorageIdentifier(documentId: String): String {
            return documentId.substringAfter(":")
        }

        /**
         * Shady function to create a nice path for documents.
         *
         * See e.g. https://stackoverflow.com/questions/13209494/how-to-get-the-full-file-path-from-uri
         */
        fun objectPath(uri: Uri): String {
            if (uri == Uri.EMPTY || uri.path == null) return ""
            try {
                val docId =
                    if (DocumentsContract.isTreeUri(uri)) DocumentsContract.getTreeDocumentId(uri) else DocumentsContract.getDocumentId(
                        uri
                    )
                return removeStorageIdentifier(docId)
            } catch (e: IllegalArgumentException) {
                // This should never happen, should throw here.
                Log.e(TAG, "Failed to create object Path from uri.", e)
            }
            return uri.path!!
        }
    }

    override fun listObjects(): List<ContentObject> {
        val files = mutableListOf<ContentObject>()

        listObjectsRecursive(rootDocumentId, files)
        return files
    }

    private fun listObjectsRecursive(folderDocumentId: String, files: MutableList<ContentObject>) {
        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(root, folderDocumentId)

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex =
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex =
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeTypeIndex =
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val lastModifiedIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            while (cursor.moveToNext()) {
                val documentId = cursor.getString(idIndex)
                val displayName = cursor.getString(nameIndex)
                val mimeType = cursor.getString(mimeTypeIndex)
                val size = cursor.getLong(sizeIndex)
                val lastModified = cursor.getLong(lastModifiedIndex)

                // Resolve directories recursively
                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    listObjectsRecursive(documentId, files)
                } else {
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(root, documentId)
                    val path = removeStorageIdentifier(documentId)
                    val relPath = documentId.substringAfter("$rootDocumentId/")

                    files.add(ContentObject(path, relPath, size, mimeType, fileUri, lastModified))
                    // Log or handle the file
                    Log.i(TAG, "File: $displayName, MIME Type: $mimeType, Uri: $fileUri")
                }
            }
        }
    }

    override fun getInputStream(uri: Uri): InputStream {
        return contentResolver.openInputStream(uri)
            ?: throw Exception("Could not open stream for $uri.")
    }

    /**
     * Opens the document at [objectPath], creating it and its parent folders when missing. If
     * opening fails, a document this call created is removed again, so no empty file with a fresh
     * timestamp is left for later syncs to skip.
     */
    override fun openForWriting(objectPath: String): OpenedDocument {
        val (parentUri, objectName) = createOrFindParent(objectPath)
        val existing = getDocumentUri(parentUri, objectName)
        val document = existing ?: createDocument(parentUri, objectName)
        return try {
            OpenedDocument(document, getOutputStream(document))
        } catch (e: Exception) {
            if (existing == null) removeAfterFailedOpen(document, e)
            throw e
        }
    }

    /** A failure to remove it is attached to [failure], which the caller still gets. */
    private fun removeAfterFailedOpen(document: Uri, failure: Exception) {
        try {
            if (!documentsRepository.deleteDocument(document)) {
                failure.addSuppressed(IOException("Failed to delete $document"))
            }
        } catch (e: Exception) {
            failure.addSuppressed(e)
        }
    }

    /**
     * Takes a complete [objectPath], e.g. Pictures/2025/picture.png, and creates the directory path
     * if it's missing and an empty Document with the trailing objectName and returns the [Uri].
     */
    internal fun createOrFindDocument(objectPath: String): Uri {
        val (parentUri, objectName) = createOrFindParent(objectPath)
        return getDocumentUri(parentUri, objectName) ?: createDocument(parentUri, objectName)
    }

    /**
     * The folder [objectPath]'s document goes in, with any missing folders created, and the
     * document's name. One walk down the path: each step queries the folder's children.
     */
    private fun createOrFindParent(objectPath: String): Pair<Uri, String> {
        val (directories, objectName) = extractPath(objectPath)
        return createOrFindDirectory(rootDocumentUri(), directories) to objectName
    }

    private fun createDocument(parentUri: Uri, name: String): Uri =
        documentsRepository.createDocument(parentUri, mimeTypeFor(name), name)
            ?: throw IOException("Failed to create file in $parentUri")

    /**
     * The type to create a document named [name] with. The storage provider appends a type's own
     * extension when the name's extension doesn't match it ("README" as text/plain becomes
     * "README.txt"), and the next sync wouldn't find the file under its key. So the type comes from
     * the name: its extension's type, or application/octet-stream, which the provider never extends.
     */
    private fun mimeTypeFor(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: OCTET_STREAM
    }

    /** Leaves the document's folders in place, even when they end up empty. */
    override fun delete(document: Uri) {
        if (!documentsRepository.deleteDocument(document)) {
            throw IOException("Failed to delete $document")
        }
    }

    private fun rootDocumentUri(): Uri = DocumentsContract.buildDocumentUriUsingTree(root, rootDocumentId)

    /**
     * Resolves or creates the [pathSegments] recursively from the [rootDocument].
     */
    private fun createOrFindDirectory(rootDocument: Uri, pathSegments: List<String>): Uri {

        var currentUri = rootDocument
        for (directory in pathSegments) {
            var uri = getDocumentUri(currentUri, directory, DocumentsContract.Document.MIME_TYPE_DIR)
            if (uri == null) {
                // A file in the folder's place would make the provider create "Camera (1)" instead,
                // and every later sync another copy. Not an IOException: retrying can't move it.
                if (getDocumentUri(currentUri, directory) != null) {
                    throw IllegalStateException(
                        "A file named '$directory' is where the folder '$directory' belongs. Move or rename it, then sync again."
                    )
                }
                uri = documentsRepository.createDocument(
                    currentUri,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    directory
                ) ?: throw Exception("Failed to create directory '$directory' under '$currentUri'.")
            }
            currentUri = uri
        }
        return currentUri
    }

    /** Opens [document], replacing what it held (see [DocumentsContractRepository.openOutputStream]). */
    private fun getOutputStream(document: Uri): OutputStream {
        return documentsRepository.openOutputStream(document)
            ?: throw IOException("Failed to open stream for $document")
    }

    private fun getDocumentUri(parentDocumentUri: Uri, documentName: String, mimeType: String? = null): Uri? {

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            root,
            DocumentsContract.getDocumentId(parentDocumentUri)
        )

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        var documentUri: Uri? = null

        contentResolver.query(childrenUri, projection, null, null, null)?.use query@{ cursor ->

            val idIndex =
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex =
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeTypeIndex =
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

            while (cursor.moveToNext()) {
                val docId = cursor.getString(idIndex)
                val name = cursor.getString(nameIndex)
                val actualMimeType = cursor.getString(mimeTypeIndex)
                // DocumentsContract does not support server-side filtering via selection/selectionArgs
                // for COLUMN_DISPLAY_NAME or COLUMN_MIME_TYPE it seems. :sad_panda:
                if (name == documentName) {
                    if (mimeType != null && actualMimeType != mimeType) {
                        continue
                    }
                    documentUri = DocumentsContract.buildDocumentUriUsingTree(parentDocumentUri, docId)
                    return@query // exits the lambda immediately
                }
            }
        }
        return documentUri
    }

    /**
     * Splits the passed [objectPath] into parent directories and the objectName.
     * TODO: Not sure, whether this should be sanitizing the path as the diff algorithm relies on the raw [objectPath]
     */
    fun extractPath(objectPath: String): Pair<List<String>, String> {
        val segments = objectPath
            .trim('/')
            .split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." }

        return Pair(segments.dropLast(1), segments.last())
    }

}
