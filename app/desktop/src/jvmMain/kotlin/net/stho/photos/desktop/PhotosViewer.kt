package net.stho.photos.desktop

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.io.files.Path
import net.stho.photos.adapter.linux.FfmImaging
import net.stho.photos.adapter.linux.JdbcSqlDrivers
import net.stho.photos.app.DesktopModel
import net.stho.photos.app.LocalLibrary
import net.stho.photos.app.MergedCatalogSource
import net.stho.photos.app.PackDirectory

/**
 * The composition root, as a value (§11).
 *
 * The only place that knows which adapter satisfies which port: a JDBC driver, the decode shim,
 * and the CLI's cache and library on this machine. There is no S3 client, no queue and no
 * credential anywhere in it — the viewer fetches nothing. A class rather than a block inside
 * `main`, so that `:tests:desktop` starts *this* and only skips the window.
 */
public class PhotosViewer(
    /** The CLI's cache: its shards, and the packs it keeps for this viewer (§7). */
    cliCache: Path,
    /** The library root the CLI syncs, which every album's `source_path` is relative to. */
    libraryRoot: Path,
    /** `libphotosdecode.so`; null for Skia alone, which reads JPEG and PNG. */
    decodeLibrary: String?,
    /** The longest edge worth decoding a photo to, in pixels: the screen's. */
    longEdge: Int,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val drivers = JdbcSqlDrivers()

    /**
     * Where the merged database is replayed to: memory, not disk (§11). A tmpfs under
     * `$XDG_RUNTIME_DIR` rather than an in-memory SQLite, so the readers the catalog opens per
     * question each see the one database — and nothing here is ever written to the CLI's cache,
     * whose lock the viewer does not take.
     */
    private val scratch: File = scratchDirectory()

    public val library: LocalLibrary = LocalLibrary(
        cliCache = cliCache,
        libraryRoot = libraryRoot,
        mergedPath = Path(scratch.resolve("merged.db").path),
        drivers = drivers,
    )

    public val thumbnails: PackDirectory = PackDirectory(library, drivers)

    private val originals = OriginalPreviews(library, decodeLibrary?.let { FfmImaging(it) }, longEdge)

    public val model: DesktopModel = DesktopModel(
        catalog = MergedCatalogSource(library.mergedPath, drivers),
        rebuilder = library,
        thumbnails = thumbnails,
        previews = originals,
        videos = originals,
        scope = scope,
    )

    public fun start(): Unit = model.start()

    override fun close() {
        scope.cancel()
        originals.close()
        library.close()
        scratch.deleteRecursively()
    }

    private companion object {
        fun scratchDirectory(): File {
            val runtime = System.getenv("XDG_RUNTIME_DIR")?.takeIf { File(it).isDirectory }
                ?: System.getProperty("java.io.tmpdir")
            // The pid says whose it is; the suffix keeps two viewers in one process — a suite's — apart.
            val name = "photos-desktop-${ProcessHandle.current().pid()}-${kotlin.uuid.Uuid.random().toString().take(8)}"
            return File(runtime, name).apply {
                mkdirs()
                deleteOnExit()
            }
        }
    }
}
