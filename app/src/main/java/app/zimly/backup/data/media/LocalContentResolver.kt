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
    fun createOutputStream(objectPath: String, mimeType: String): OutputStream

    /**
     * Deletes the document at [objectPath], such as one whose download failed partway. Does
     * nothing when there is no such document.
     */
    fun delete(objectPath: String)
}


