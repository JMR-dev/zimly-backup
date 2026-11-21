package app.zimly.backup.data.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import java.io.InputStream
import java.io.OutputStream

/**
 * Common interface shared by all implementations or wrappers of [ContentResolver] that queries local
 * [ContentObject]s, like media or documents.
 */
interface LocalContentResolver {
    fun getInputStream(uri: Uri): InputStream
    fun listObjects(): List<ContentObject>

    companion object {

        /**
         * Provides the correct [LocalContentResolver] based on [ContentType].
         */
        fun get(context: Context, type: ContentType, scope: String) =
            when (type) {
                ContentType.MEDIA -> LocalMediaResolverImpl(context, scope)
                ContentType.FOLDER -> LocalDocumentsResolver(context, scope.toUri())
            }
    }
}

interface WriteableContentResolver : LocalContentResolver {

    /**
     * Opens the document at [objectPath] for writing, replacing what it held; creates it, and its
     * folders, when missing.
     */
    fun openForWriting(objectPath: String): OpenedDocument

    /**
     * Deletes [document], such as one whose download failed partway. It goes by the document's
     * own Uri, not its name, so it can't miss a document the storage provider named differently.
     */
    fun delete(document: Uri)
}

/** A document opened for writing: where it is, and the stream that writes it. */
class OpenedDocument(val uri: Uri, val stream: OutputStream)

