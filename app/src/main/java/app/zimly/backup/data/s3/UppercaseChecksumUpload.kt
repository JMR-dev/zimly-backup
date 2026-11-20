package app.zimly.backup.data.s3

import android.util.Log
import io.minio.AbortMultipartUploadArgs
import io.minio.Checksum
import io.minio.CompleteMultipartUploadArgs
import io.minio.CreateMultipartUploadArgs
import io.minio.Http
import io.minio.MinioAsyncClient
import io.minio.UploadPartArgs
import io.minio.messages.Part
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import okio.BufferedSource
import okio.buffer
import okio.source
import java.io.EOFException
import java.io.InputStream

/**
 * A multipart upload that names its checksum algorithm in uppercase (`CRC32C`).
 *
 * minio-java 9 always sends lowercase algorithm names (`crc32c`), and some S3 servers reject them.
 * Garage, for one, refuses CreateMultipartUpload with "invalid checksum algorithm". This drives the
 * SDK's public low-level multipart calls instead, where every header is ours. It is only used as a
 * retry after such a rejection, see [MinioRepository.put].
 *
 * It works for any object size, because a lone part may be any size, even empty. Parts go up one at
 * a time through one reused buffer, the same memory the SDK's own upload uses.
 */
internal class UppercaseChecksumUpload(
    private val client: MinioAsyncClient,
    private val bucket: String,
) {

    suspend fun upload(stream: InputStream, name: String, contentType: String, size: Long) {
        val uploadId = create(name, contentType)
        try {
            val parts = uploadParts(stream.source().buffer(), name, uploadId, size)
            complete(name, uploadId, parts)
        } catch (e: Throwable) {
            // Unfinished uploads keep their parts, and providers bill for them. Abort even when
            // cancelled, then report the original failure.
            withContext(NonCancellable) { abortQuietly(name, uploadId) }
            throw e
        }
    }

    private suspend fun create(name: String, contentType: String): String {
        val args = CreateMultipartUploadArgs.builder()
            .bucket(bucket)
            .`object`(name)
            .headers(Http.Headers(CONTENT_TYPE, contentType, CHECKSUM_ALGORITHM, CRC32C))
            .build()
        return client.createMultipartUpload(args).await().result().uploadId()
    }

    /** Sends the object part by part; an empty object is one empty part. */
    private suspend fun uploadParts(
        source: BufferedSource,
        name: String,
        uploadId: String,
        size: Long
    ): List<Part> {
        val partSize = partSize(size)
        // Small objects need no more buffer than their own size.
        val buffer = ByteArray(Math.toIntExact(minOf(partSize, size)))
        val parts = mutableListOf<Part>()
        var remaining = size
        do {
            val length = Math.toIntExact(minOf(partSize, remaining))
            withContext(Dispatchers.IO) { source.readExactly(buffer, length) }
            // Awaited before the buffer is refilled: the request body references it, not a copy.
            parts += uploadPart(name, uploadId, parts.size + 1, buffer, length)
            remaining -= length
        } while (remaining > 0)
        return parts
    }

    private suspend fun uploadPart(
        name: String,
        uploadId: String,
        partNumber: Int,
        buffer: ByteArray,
        length: Int
    ): Part {
        val crc32c = crc32c(buffer, length)
        val args = UploadPartArgs.builder()
            .bucket(bucket)
            .`object`(name)
            .uploadId(uploadId)
            .partNumber(partNumber)
            .data(buffer, length)
            // With a checksum header present, the SDK adds no lowercase one of its own.
            .headers(Http.Headers(CHECKSUM_CRC32C, crc32c, SDK_CHECKSUM_ALGORITHM, CRC32C))
            .build()
        val etag = client.uploadPart(args).await().part().etag()
        // CompleteMultipartUpload must repeat each part's checksum, as declared at create.
        return Part(partNumber, etag, null, crc32c, null, null, null)
    }

    private suspend fun complete(name: String, uploadId: String, parts: List<Part>) {
        val args = CompleteMultipartUploadArgs.builder()
            .bucket(bucket)
            .`object`(name)
            .uploadId(uploadId)
            .parts(parts.toTypedArray())
            .build()
        client.completeMultipartUpload(args).await()
    }

    private suspend fun abortQuietly(name: String, uploadId: String) {
        val args = AbortMultipartUploadArgs.builder()
            .bucket(bucket)
            .`object`(name)
            .uploadId(uploadId)
            .build()
        try {
            client.abortMultipartUpload(args).await()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to abort multipart upload $uploadId of $name", e)
        }
    }

    private companion object {
        val TAG: String? = UppercaseChecksumUpload::class.simpleName

        const val CONTENT_TYPE = "Content-Type"
        const val CHECKSUM_ALGORITHM = "x-amz-checksum-algorithm"
        const val SDK_CHECKSUM_ALGORITHM = "x-amz-sdk-checksum-algorithm"
        const val CHECKSUM_CRC32C = "x-amz-checksum-crc32c"
        const val CRC32C = "CRC32C"

        /** Base64 CRC32C of the first [length] bytes, as `x-amz-checksum-crc32c` expects. */
        fun crc32c(buffer: ByteArray, length: Int): String {
            val hasher = Checksum.CRC32C()
            hasher.update(buffer, 0, length)
            return Checksum.base64String(hasher.sum())
        }

        /** Fills the first [length] bytes of [buffer], or throws if the stream ends first. */
        fun BufferedSource.readExactly(buffer: ByteArray, length: Int) {
            var offset = 0
            while (offset < length) {
                val read = read(buffer, offset, length - offset)
                if (read == -1) throw EOFException("Stream ended after $offset of $length bytes")
                offset += read
            }
        }
    }
}
