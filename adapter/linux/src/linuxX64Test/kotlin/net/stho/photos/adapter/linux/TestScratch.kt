package net.stho.photos.adapter.linux

import kotlin.random.Random
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

/**
 * An absolute scratch root for tests that need real files.
 *
 * `SystemTemporaryDirectory` is not guaranteed absolute on Kotlin/Native, and when it is not,
 * every scratch path resolves against the test's working directory — which is the module
 * directory. Three such files were committed to this repository before anyone noticed, one of
 * them a 307 KB binary, because they looked like ordinary untracked output to `git add -A`.
 *
 * The domain has the same helper in its own `commonTest`; it is `internal` to that module, so
 * this is the adapter's copy rather than a shared one.
 */
internal val scratchRoot: Path =
    SystemTemporaryDirectory.takeIf { it.isAbsolute } ?: Path("/tmp")

/**
 * A scratch directory that cleans up after itself.
 *
 * Kept short — `photos-<label>-<8 hex>` — because the D-Bus harness puts an `AF_UNIX` socket
 * inside one, and `sockaddr_un` truncates at 108 bytes with no error worth reading.
 */
internal fun <R> withScratchDirectory(label: String, body: (Path) -> R): R {
    val directory = scratchDirectory(label)
    try {
        return body(directory)
    } finally {
        deleteTree(directory)
    }
}

internal fun scratchDirectory(label: String): Path {
    val suffix = Random.nextLong().toULong().toString(16).padStart(8, '0').takeLast(8)
    val directory = Path(scratchRoot, "photos-$label-$suffix")
    SystemFileSystem.createDirectories(directory)
    return directory
}

/**
 * Removes a scratch tree, tolerating entries that vanish underneath it — a `dbus-daemon` that
 * has just been asked to stop removes its own socket, and losing that race is not a test
 * failure.
 */
internal fun deleteTree(path: Path) {
    val metadata = SystemFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) SystemFileSystem.list(path).forEach(::deleteTree)
    try {
        SystemFileSystem.delete(path, mustExist = false)
    } catch (ignored: IOException) {
        // Gone already, or about to be: either way there is nothing left to remove.
    }
}
