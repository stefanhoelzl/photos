package net.stho.photos.library

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.write
import kotlinx.io.writeString
import net.stho.photos.scratchPath

private val temporaryDirectories = mutableListOf<Path>()

/** A scratch directory, unique per test and removed by [deleteTemporaryDirectories]. */
internal fun temporaryDirectory(label: String = "library"): Path =
    scratchPath(label)
        .also(SystemFileSystem::createDirectories)
        .also(temporaryDirectories::add)

/**
 * Removes every scratch directory made so far — what each suite's `@AfterTest` calls.
 *
 * Worth doing rather than trusting the operating system: scratch files from this repository have
 * been committed by accident once already.
 */
internal fun deleteTemporaryDirectories() {
    for (directory in temporaryDirectories) directory.deleteRecursively()
    temporaryDirectories.clear()
}

private fun Path.deleteRecursively() {
    if (SystemFileSystem.metadataOrNull(this)?.isDirectory == true) {
        for (child in SystemFileSystem.list(this)) child.deleteRecursively()
    }
    SystemFileSystem.delete(this, mustExist = false)
}

/**
 * Builds a small library on disk and answers its root. Paths are `dir/file`; a bare name is a
 * root file.
 */
internal fun library(vararg paths: String, ignore: String? = null): Path {
    val root = temporaryDirectory()
    for (path in paths) {
        val file = Path(root, *path.split('/').toTypedArray())
        file.parent?.let(SystemFileSystem::createDirectories)
        file.writeText("x")
    }
    if (ignore != null) Path(root, IgnoreRules.FILENAME).writeText(ignore)
    return root
}

internal fun Path.writeText(text: String) {
    SystemFileSystem.sink(this).buffered().use { it.writeString(text) }
}

internal fun Path.writeBytes(bytes: ByteArray) {
    SystemFileSystem.sink(this).buffered().use { it.write(bytes) }
}
