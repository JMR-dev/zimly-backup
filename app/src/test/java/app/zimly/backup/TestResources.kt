package app.zimly.backup

/**
 * Reads a classpath test resource (for example "/testdata/test_image.png") fully into memory, and
 * closes it. Upload tests hand put() `{ ByteArrayInputStream(bytes) }`, so every call, including a
 * retry, gets a fresh stream, and the size is exact.
 */
fun readTestResource(path: String): ByteArray =
    requireNotNull(object {}.javaClass.getResourceAsStream(path)) { "Missing test resource $path" }
        .use { it.readBytes() }
