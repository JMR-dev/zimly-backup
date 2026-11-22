package app.zimly.backup.data.s3

import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * The part uploads of the upload in flight, so a cancelled or failed upload can stop them.
 *
 * With parallel parts, minio reads several parts ahead into memory, so stopping the source no
 * longer makes it fail: it would send what it had read and complete the upload. Cancelling the
 * part uploads fails them, and minio then aborts the multipart upload.
 *
 * Only UploadPart requests (a PUT with `partNumber` and `uploadId`) are recorded and cancelled:
 * cancelling minio's own AbortMultipartUpload would leave the parts on the server, and cancelling
 * Create or CompleteMultipartUpload would leave the outcome to timing. Once [cancelInFlight] has
 * run, a part upload that starts later fails at once, so no part lands after the abort. [reset]
 * clears that for the next upload.
 */
internal class UploadCalls(
    /** Whether an upload is in flight; only its calls are recorded. */
    private val uploading: () -> Boolean,
) : Interceptor {

    private val calls: MutableSet<Call> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var cancelled = false

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!uploading() || !request.isUploadPart()) return chain.proceed(request)
        if (cancelled) throw IOException("Upload cancelled")
        val call = chain.call()
        calls += call
        try {
            return chain.proceed(request)
        } finally {
            calls -= call
        }
    }

    /** Cancels the part uploads in flight, and fails any that start later until [reset]. */
    fun cancelInFlight() {
        cancelled = true
        calls.toList().forEach(Call::cancel)
    }

    /** Lets the next upload's parts through. */
    fun reset() {
        cancelled = false
    }

    private fun Request.isUploadPart(): Boolean =
        method == "PUT" && url.queryParameter("partNumber") != null && url.queryParameter("uploadId") != null
}
