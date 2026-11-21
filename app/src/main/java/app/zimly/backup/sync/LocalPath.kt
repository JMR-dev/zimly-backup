package app.zimly.backup.sync

/** Characters shared storage can't hold in a name (FAT rules, as Android's FileUtils applies them). */
private const val INVALID_NAME_CHARS = "\"*:<>?\\|"
private const val DELETE_CHAR = '\u007F'
private const val LAST_CONTROL_CHAR = '\u001F'

/**
 * The path, relative to the sync's folder, that the phone stores an S3 [key] under, or null when
 * the key names no file of its own: one with an empty, "." or ".." segment ("x//photo.jpg",
 * "x/./photo.jpg", "..") would land on another key's file or outside the folder.
 *
 * The storage provider replaces each character it can't store with '_', so the diff must
 * compare, and the download must write, that same name; otherwise the file looks missing and is
 * downloaded again on every sync.
 */
internal fun localPathOf(key: String): String? {
    val segments = key.split('/')
    if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
    return segments.joinToString("/") { storableName(it) }
}

private fun storableName(segment: String): String =
    segment.map { if (it.isStorable()) it else '_' }.joinToString("")

private fun Char.isStorable(): Boolean =
    this !in INVALID_NAME_CHARS && this > LAST_CONTROL_CHAR && this != DELETE_CHAR
