package app.zimly.backup.data.s3

import io.minio.PutObjectArgs
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * [partSize] must split objects exactly the way minio-java's own `putObject` does, so the
 * hand-written retry upload behaves like the SDK path. The SDK is the oracle.
 */
class PartSizeTest {

    @Test
    fun `matches the SDK for every size class`() {
        val sizes = listOf(
            0L,
            1L,
            FIVE_MIB - 1,
            FIVE_MIB,
            FIVE_MIB + 1,
            100 * MIB,
            48 * GIB,
            60 * GIB,
            5 * 1024 * GIB, // 5 TiB, the S3 maximum
        )
        sizes.forEach { size ->
            assertEquals("part size for $size bytes", sdkPartSize(size), partSize(size))
        }
    }

    private fun sdkPartSize(size: Long): Long = PutObjectArgs.builder()
        .bucket("bucket")
        .`object`("object")
        .stream(ByteArrayInputStream(ByteArray(0)), size, -1)
        .build()
        .partSize()

    private companion object {
        const val MIB = 1024L * 1024
        const val GIB = 1024 * MIB
        const val FIVE_MIB = 5 * MIB
    }
}
