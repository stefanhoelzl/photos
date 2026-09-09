package net.stho.photos.library

import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** One directory of the library that holds media, with the files it holds. */
public data class LibraryAlbum(
    /** The directory itself. `$LIBRARY_ROOT` when the root holds loose files. */
    val directory: Path,
    /**
     * Path relative to the library root; empty for the root itself. This is what
     * `album_info.source_path` records (§3).
     */
    val relativePath: String,
    /** Regular files, excluding everything `.photosignore` matched. Sorted. */
    val files: List<Path>,
)

/** What one walk found. */
public data class LibraryContents(
    val albums: List<LibraryAlbum> = emptyList(),
    val ignoredFiles: List<Path> = emptyList(),
    val prunedDirectories: List<Path> = emptyList(),
    /** Parallel to [rules], counting what each excluded. */
    val ruleUsage: List<Int> = emptyList(),
    val rules: IgnoreRules = IgnoreRules(),
) {
    public val fileCount: Int get() = albums.sumOf { it.files.size }

    /**
     * Rules that excluded nothing. A rule matching nothing is either a typo or a leftover, and
     * both are worth naming — neither is distinguishable from a working rule otherwise.
     */
    public val unusedRules: List<IgnoreRule>
        get() = rules.rules.filterIndexed { index, _ -> ruleUsage[index] == 0 }
}

/**
 * Walks `$LIBRARY_ROOT` and applies `.photosignore`.
 *
 * Ignoring lives here and nowhere else. `MediaClassifier` never sees an excluded file, so it
 * carries no exclusion logic at all — and §7's other two consumers of "what is in the library",
 * the deletion sweep and the byte-size change assertion, inherit the same view rather than each
 * keeping a copy of the rules. Two components that disagreed about what the library contains is
 * exactly the class of bug §7 created the shared module to prevent.
 *
 * Traversal sits below D rather than in the pipeline because it is a CLI concept: the phone
 * uploads from `PHAssetCollection` and has no library tree to walk.
 */
public class LibraryWalker(
    public val root: Path,
    public val rules: IgnoreRules,
) {

    /**
     * Loads `.photosignore` from the root. Throws only when the file exists and cannot be read;
     * absent means no exclusions.
     */
    public constructor(root: Path) : this(root, root.readIgnoreRules())

    /**
     * The root as text, with any trailing separator gone.
     *
     * Compared as a standardised string rather than as a `Path`, because equality is sensitive to
     * trailing slashes and to how the root was spelled on the command line.
     */
    private val rootPath: String = standardized(root)

    public fun walk(): LibraryContents {
        val albums = mutableListOf<LibraryAlbum>()
        val ignoredFiles = mutableListOf<Path>()
        val prunedDirectories = mutableListOf<Path>()
        val ruleUsage = MutableList(rules.rules.size) { 0 }

        fun visit(directory: Path) {
            // No hidden-file filter: there is no built-in dot rule, so `.dtrash/` is skipped only
            // because `.photosignore` says so.
            val entries = try {
                SystemFileSystem.list(directory)
            } catch (_: IOException) {
                emptyList<Path>()
            }

            val files = mutableListOf<Path>()
            val subdirectories = mutableListOf<Path>()

            for (entry in entries) {
                val name = entry.name
                // The one exclusion that is not in the file: the file itself.
                if (name == IgnoreRules.FILENAME && isRoot(directory)) continue

                val metadata = SystemFileSystem.metadataOrNull(entry)
                val isDirectory = metadata?.isDirectory == true
                if (!isDirectory && metadata?.isRegularFile != true) continue

                val index = rules.matchIndex(name, relative(entry), isDirectory)
                if (index != null) {
                    ruleUsage[index]++
                    if (isDirectory) prunedDirectories += entry else ignoredFiles += entry
                    continue
                }

                if (isDirectory) subdirectories += entry else files += entry
            }

            if (files.isNotEmpty()) {
                albums += LibraryAlbum(
                    directory = directory,
                    relativePath = relative(directory),
                    files = files.sortedBy(Path::toString),
                )
            }
            for (subdirectory in subdirectories.sortedBy(Path::toString)) visit(subdirectory)
        }

        visit(root)

        // Sorted on the way out, so two runs of the same library report the same thing in the
        // same order — a report nobody can diff is a report nobody reads.
        return LibraryContents(
            albums = albums.sortedBy(LibraryAlbum::relativePath),
            ignoredFiles = ignoredFiles.sortedBy(Path::toString),
            prunedDirectories = prunedDirectories.sortedBy(Path::toString),
            ruleUsage = ruleUsage,
            rules = rules,
        )
    }

    private fun isRoot(directory: Path): Boolean = standardized(directory) == rootPath

    private fun relative(path: Path): String {
        val text = standardized(path)
        if (!text.startsWith(rootPath)) return path.name
        return text.drop(rootPath.length).dropWhile { it == '/' }
    }
}

private fun standardized(path: Path): String =
    path.toString().trimEnd('/').ifEmpty { "/" }
