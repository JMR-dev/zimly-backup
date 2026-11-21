package app.zimly.backup.data.s3

import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap

/**
 * The HTTP calls of the upload in flight, so a cancelled upload can stop them.
 *
 * With parallel parts, minio reads several parts ahead into memory, so stopping the source no
 * longer makes it fail: it would send what it had read and complete the upload. Cancelling these
 * calls fails the parts in flight, and minio then aborts the multipart upload.
 */
internal class UploadCalls(
    /** Whether an upload is in flight; only its calls are recorded. */
    private val uploading: () -> Boolean,
) : Interceptor {

    private val calls: MutableSet<Call> = ConcurrentHashMap.newKeySet()

    override fun intercept(chain: Interceptor.Chain): Response {
        if (!uploading()) return chain.proceed(chain.request())
        val call = chain.call()
        calls += call
        try {
            return chain.proceed(chain.request())
        } finally {
            calls -= call
        }
    }

    /** Cancels the calls in flight now; calls made afterwards, such as minio's abort, go ahead. */
    fun cancelInFlight() {
        calls.toList().forEach(Call::cancel)
    }
}
