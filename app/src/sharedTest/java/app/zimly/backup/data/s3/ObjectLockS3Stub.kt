package app.zimly.backup.data.s3

import okhttp3.HttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.CRC32C
import java.util.zip.Checksum

/**
 * A MockWebServer [Dispatcher] that stands in for a single S3 bucket, modelled on Backblaze B2.
 *
 * * With [objectLockEnabled], every object or part PUT must carry an integrity header:
 *   `Content-MD5` or an `x-amz-checksum-<algorithm>` value. Without one it answers 400
 *   `InvalidRequest` with B2's exact message.
 * * Supplied hashes are always checked, as real servers do: a wrong `Content-MD5` gives
 *   `BadDigest`, a wrong `x-amz-checksum-*` gives `XAmzContentChecksumMismatch`.
 * * With [uppercaseAlgorithmOnly], CreateMultipartUpload accepts `x-amz-checksum-algorithm` only
 *   as an exact uppercase name, the way Garage does.
 * * [failPart] answers that part number with 403 `AccessDenied`, an error nothing should retry.
 * * [holdPart] blocks that part's response until [releasePart] is counted down, so a test can
 *   cancel an upload mid-flight. [partHeld] is counted down when that request arrives.
 *
 * Path-style requests only: `/<bucket>/<key>`.
 */
class ObjectLockS3Stub(
    private val objectLockEnabled: Boolean,
    private val uppercaseAlgorithmOnly: Boolean = false,
    private val failPart: Int? = null,
    private val holdPart: Int? = null,
    /** How long each part's request is held before it's answered, so overlapping parts show. */
    private val partDelayMillis: Long = 0,
) : Dispatcher() {

    private val partsInFlight = AtomicInteger()

    /** The most part requests the stub was handling at once. */
    val maxPartsInFlight = AtomicInteger()

    data class StoredObject(val bytes: ByteArray, val contentType: String?)

    data class CreateRecord(val key: String, val checksumAlgorithm: String?)

    private class Upload(val key: String, val contentType: String?) {
        val parts = ConcurrentHashMap<Int, ByteArray>()
    }

    /** Completed objects by key. */
    val objects = ConcurrentHashMap<String, StoredObject>()

    /** Every CreateMultipartUpload request, accepted or rejected, in order. */
    val creates = CopyOnWriteArrayList<CreateRecord>()

    /** Upload IDs of every AbortMultipartUpload request. */
    val aborts = CopyOnWriteArrayList<String>()

    /** Counted down when the request for [holdPart] arrives. */
    val partHeld = CountDownLatch(1)

    /** The [holdPart] request isn't answered until this is counted down. */
    val releasePart = CountDownLatch(1)

    private val uploads = ConcurrentHashMap<String, Upload>()
    private val uploadCounter = AtomicInteger()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val url = request.requestUrl ?: return s3Error(400, "InvalidRequest", "Request has no URL")
        val uploadId = url.queryParameter("uploadId")
        return when {
            request.method == "POST" && "uploads" in url.queryParameterNames -> createUpload(request, url)
            request.method == "PUT" && uploadId != null -> uploadPart(request, url, uploadId)
            request.method == "PUT" -> putObject(request, url)
            request.method == "POST" && uploadId != null -> completeUpload(request, url, uploadId)
            request.method == "DELETE" && uploadId != null -> abortUpload(uploadId)
            else -> s3Error(405, "MethodNotAllowed", "Stub can't handle ${request.method} ${request.path}")
        }
    }

    private fun putObject(request: RecordedRequest, url: HttpUrl): MockResponse {
        val body = request.body.readByteArray()
        integrityError(request, body)?.let { return it }
        objects[objectKey(url)] = StoredObject(body, request.getHeader("Content-Type"))
        return accepted(request, body)
    }

    private fun createUpload(request: RecordedRequest, url: HttpUrl): MockResponse {
        val key = objectKey(url)
        val algorithm = request.getHeader(CHECKSUM_ALGORITHM_HEADER)
        creates += CreateRecord(key, algorithm)
        if (uppercaseAlgorithmOnly && algorithm != null && algorithm !in GARAGE_ALGORITHMS) {
            return s3Error(400, "InvalidRequest", "Bad request: invalid checksum algorithm")
        }
        val uploadId = "upload-${uploadCounter.incrementAndGet()}"
        uploads[uploadId] = Upload(key, request.getHeader("Content-Type"))
        return xml(
            "<InitiateMultipartUploadResult xmlns=\"$S3_NAMESPACE\">" +
                "<Bucket>${bucket(url)}</Bucket><Key>$key</Key>" +
                "<UploadId>$uploadId</UploadId></InitiateMultipartUploadResult>"
        )
    }

    private fun uploadPart(request: RecordedRequest, url: HttpUrl, uploadId: String): MockResponse {
        val upload = uploads[uploadId] ?: return noSuchUpload(uploadId)
        val partNumber = url.queryParameter("partNumber")?.toIntOrNull()
            ?: return s3Error(400, "InvalidArgument", "Missing partNumber")
        val body = request.body.readByteArray()
        if (partNumber == failPart) {
            return s3Error(403, "AccessDenied", "Injected failure for part $partNumber")
        }
        if (partNumber == holdPart) {
            partHeld.countDown()
            releasePart.await(HOLD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        holdForOverlap()
        integrityError(request, body)?.let { return it }
        upload.parts[partNumber] = body
        return accepted(request, body)
    }

    /** Counts this part as in flight for [partDelayMillis], recording the most at once. */
    private fun holdForOverlap() {
        val inFlight = partsInFlight.incrementAndGet()
        maxPartsInFlight.accumulateAndGet(inFlight, ::maxOf)
        try {
            if (partDelayMillis > 0) Thread.sleep(partDelayMillis)
        } finally {
            partsInFlight.decrementAndGet()
        }
    }

    private fun completeUpload(request: RecordedRequest, url: HttpUrl, uploadId: String): MockResponse {
        val upload = uploads[uploadId] ?: return noSuchUpload(uploadId)
        val partNumbers = PART_NUMBER.findAll(request.body.readUtf8())
            .map { it.groupValues[1].toInt() }
            .toList()
        val missing = partNumbers.firstOrNull { !upload.parts.containsKey(it) }
        if (missing != null) return s3Error(400, "InvalidPart", "Part $missing was never uploaded")

        objects[upload.key] = StoredObject(joinParts(upload, partNumbers), upload.contentType)
        uploads.remove(uploadId)
        return xml(
            "<CompleteMultipartUploadResult xmlns=\"$S3_NAMESPACE\">" +
                "<Bucket>${bucket(url)}</Bucket><Key>${upload.key}</Key>" +
                "<ETag>\"stub-multipart-etag\"</ETag></CompleteMultipartUploadResult>"
        )
    }

    private fun abortUpload(uploadId: String): MockResponse {
        uploads.remove(uploadId)
        aborts += uploadId
        return MockResponse().setResponseCode(204)
    }

    /**
     * The error a lock-enforcing, hash-checking server returns for this PUT body, or null if the
     * PUT is accepted.
     */
    private fun integrityError(request: RecordedRequest, body: ByteArray): MockResponse? {
        val contentMd5 = request.getHeader("Content-MD5")
        val checksums = suppliedChecksums(request)

        if (objectLockEnabled && contentMd5 == null && checksums.isEmpty()) {
            return s3Error(400, "InvalidRequest", OBJECT_LOCK_MESSAGE)
        }
        if (contentMd5 != null && contentMd5 != base64(digest("MD5", body))) {
            return s3Error(400, "BadDigest", "The Content-MD5 you specified did not match what we received.")
        }
        for ((algorithm, value) in checksums) {
            val expected = checksum(algorithm, body)
                ?: return s3Error(400, "InvalidRequest", "Stub can't verify x-amz-checksum-$algorithm")
            if (value != expected) {
                return s3Error(
                    400,
                    "XAmzContentChecksumMismatch",
                    "The provided 'x-amz-checksum' header does not match what was computed."
                )
            }
        }
        return null
    }

    /** `x-amz-checksum-<algorithm>` value headers, keyed by lowercase algorithm name. */
    private fun suppliedChecksums(request: RecordedRequest): Map<String, String> =
        request.headers.names()
            .map { it.lowercase() }
            .filter { it.startsWith(CHECKSUM_PREFIX) && it !in NON_VALUE_CHECKSUM_HEADERS }
            .associate { it.removePrefix(CHECKSUM_PREFIX) to request.getHeader(it)!! }

    /** 200 with an ETag, echoing the checksum headers back the way S3 does. */
    private fun accepted(request: RecordedRequest, body: ByteArray): MockResponse {
        val response = MockResponse()
            .setResponseCode(200)
            .setHeader("ETag", "\"${hex(digest("MD5", body))}\"")
        suppliedChecksums(request).forEach { (algorithm, value) ->
            response.setHeader("$CHECKSUM_PREFIX$algorithm", value)
        }
        return response
    }

    private fun noSuchUpload(uploadId: String) =
        s3Error(404, "NoSuchUpload", "Upload $uploadId does not exist")

    companion object {
        const val OBJECT_LOCK_MESSAGE =
            "Content-MD5 OR x-amz-checksum- HTTP header is required for Put Object requests " +
                "with Object Lock parameters"

        private const val HOLD_TIMEOUT_SECONDS = 30L
        private const val S3_NAMESPACE = "http://s3.amazonaws.com/doc/2006-03-01/"
        private const val CHECKSUM_PREFIX = "x-amz-checksum-"
        private const val CHECKSUM_ALGORITHM_HEADER = "x-amz-checksum-algorithm"
        private val NON_VALUE_CHECKSUM_HEADERS = setOf("x-amz-checksum-algorithm", "x-amz-checksum-type")

        /** Garage matches these exactly, so `crc32c` is rejected. */
        private val GARAGE_ALGORITHMS = setOf("CRC32", "CRC32C", "SHA1", "SHA256")

        private val PART_NUMBER = Regex("<PartNumber>(\\d+)</PartNumber>")

        private fun objectKey(url: HttpUrl) = url.pathSegments.drop(1).joinToString("/")

        private fun bucket(url: HttpUrl) = url.pathSegments.first()

        private fun joinParts(upload: Upload, partNumbers: List<Int>): ByteArray {
            val joined = ByteArrayOutputStream()
            partNumbers.forEach { joined.write(upload.parts.getValue(it)) }
            return joined.toByteArray()
        }

        /** Base64 checksum value for an `x-amz-checksum-<algorithm>` header, or null if unknown. */
        private fun checksum(algorithm: String, body: ByteArray): String? = when (algorithm) {
            "crc32" -> base64(crc(CRC32(), body))
            "crc32c" -> base64(crc(CRC32C(), body))
            "sha1" -> base64(digest("SHA-1", body))
            "sha256" -> base64(digest("SHA-256", body))
            else -> null
        }

        /** 32-bit CRCs are sent as their 4 big-endian bytes. */
        private fun crc(checksum: Checksum, body: ByteArray): ByteArray {
            checksum.update(body, 0, body.size)
            return ByteBuffer.allocate(4).putInt(checksum.value.toInt()).array()
        }

        private fun digest(algorithm: String, body: ByteArray): ByteArray =
            MessageDigest.getInstance(algorithm).digest(body)

        private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        private fun xml(body: String): MockResponse = MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/xml")
            .setBody("<?xml version=\"1.0\" encoding=\"UTF-8\"?>$body")

        private fun s3Error(status: Int, code: String, message: String): MockResponse = MockResponse()
            .setResponseCode(status)
            .setHeader("Content-Type", "application/xml")
            .setBody(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>$code</Code>" +
                    "<Message>$message</Message><RequestId>stub</RequestId><HostId>stub</HostId></Error>"
            )
    }
}
