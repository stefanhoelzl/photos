@file:OptIn(ExperimentalForeignApi::class)

package net.stho.photos.adapter.ios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import net.stho.photos.ports.Paths
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask

/**
 * §7's `Paths`, as an iOS app container defines them.
 *
 * **Application Support, not Caches**, and that is a decision rather than a habit. §6 promises
 * that nothing is ever auto-evicted and that every album's thumbnail pack is kept permanently —
 * the whole cache vocabulary on the album row, from "grey, nothing held" to "full green, works
 * offline", is a claim about what is still on disk. `Library/Caches` is purgeable by the system
 * under disk pressure, so a row could read complete and then silently not be, which is the one
 * thing that vocabulary must never do. Application Support is not purged.
 *
 * The price is that Application Support **is** backed up to iCloud by default, and §4's layout
 * is ~0.5 GB of packs before a single viewing image lands. All of it is re-downloadable from
 * the zone, so every byte would be paid for twice for no gain — hence the exclusion flag, set
 * once on the directory, which iOS applies to everything beneath it.
 */
public class ContainerPaths(
    /** Injected so a test can point this somewhere it is allowed to write. */
    container: NSURL = applicationSupport(),
) : Paths {

    private val root: NSURL =
        requireNotNull(container.URLByAppendingPathComponent(APPLICATION, isDirectory = true)) {
            "could not name a subdirectory of $container"
        }

    override val cacheRoot: String = requireNotNull(root.path) { "no filesystem path for $root" }

    /**
     * The same directory, deliberately.
     *
     * Nothing reads it: the app's only configuration is the credential, and that lives in the
     * Keychain rather than in a file. XDG splits cache from config because a Linux user expects
     * `~/.config` to survive an `rm -rf ~/.cache`; an app container has no such convention, and
     * inventing a second empty directory to honour one would be cargo cult.
     */
    override val configRoot: String = cacheRoot

    init {
        memScoped {
            val failure = alloc<ObjCObjectVar<NSError?>>()
            NSFileManager.defaultManager.createDirectoryAtURL(
                url = root,
                withIntermediateDirectories = true,
                attributes = null,
                error = failure.ptr,
            )
            failure.value?.let { error("could not create $cacheRoot: ${it.localizedDescription}") }
            // Set after creation and only ever on the root: iOS applies it to the whole subtree,
            // so `blobs/` and `packs/` inherit it without this having to know they exist.
            root.setResourceValue(
                value = true,
                forKey = NSURLIsExcludedFromBackupKey,
                error = failure.ptr,
            )
            failure.value?.let {
                error("could not exclude $cacheRoot from backup: ${it.localizedDescription}")
            }
        }
    }

    public companion object {
        /** The one directory name this application owns, matching the bundle id (§6). */
        private const val APPLICATION: String = "net.stho.photos"

        /**
         * `Library/Application Support` inside this app's container.
         *
         * `URLsForDirectory` returns a list because the same query on macOS spans domains; on
         * iOS the user domain holds exactly one, and no result at all would mean a container
         * that does not exist, which is not a state worth carrying a null through.
         */
        public fun applicationSupport(): NSURL =
            NSFileManager.defaultManager.URLsForDirectory(
                directory = NSApplicationSupportDirectory,
                inDomains = NSUserDomainMask,
            ).firstOrNull() as? NSURL
                ?: error("no Application Support directory in this app's container")
    }
}
