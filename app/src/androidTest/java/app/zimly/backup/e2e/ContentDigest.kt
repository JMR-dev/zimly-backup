package app.zimly.backup.e2e

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.random.Random

private const val CHUNK_BYTES = 1024 * 1024

/**
 * What a stored object must match: its size and SHA-256. Comparing digests keeps a large test
 * video out of memory, where two full copies could exhaust the test process's heap.
 */
internal data class ContentDigest(val size: Long, val sha256: String)

internal fun digestOf(bytes: ByteArray): ContentDigest =
    ContentDigest(bytes.size.toLong(), sha256().apply { update(bytes) }.hex())

/** Writes [size] pseudo-random bytes from [seed] to [output], 1 MiB at a time, and returns their digest. */
internal fun writeRandom(output: OutputStream, size: Long, seed: Int): ContentDigest {
    val random = Random(seed)
    val digest = sha256()
    val chunk = ByteArray(CHUNK_BYTES)
    var remaining = size
    while (remaining > 0) { // Ends: every pass writes at least one byte.
        val count = minOf(remaining, CHUNK_BYTES.toLong()).toInt()
        random.nextBytes(chunk, 0, count)
        digest.update(chunk, 0, count)
        output.write(chunk, 0, count)
        remaining -= count
    }
    return ContentDigest(size, digest.hex())
}

/**
 * Reads [input] to its end and returns its digest. Stops with an error past [maxBytes], so a
 * stream longer than expected can't run on.
 */
internal fun digestOf(input: InputStream, maxBytes: Long): ContentDigest {
    val digest = sha256()
    val buffer = ByteArray(CHUNK_BYTES)
    var size = 0L
    while (size <= maxBytes) {
        val read = input.read(buffer)
        if (read < 0) return ContentDigest(size, digest.hex())
        digest.update(buffer, 0, read)
        size += read
    }
    throw AssertionError("Stream is longer than the expected $maxBytes bytes")
}

private fun sha256(): MessageDigest = MessageDigest.getInstance("SHA-256")

private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }
