package net.stho.photos.app

import kotlinx.io.IOException
import net.stho.photos.PhotosFailure
import net.stho.photos.S3HttpFailure
import net.stho.photos.StorageUnreachableFailure
import net.stho.photos.catalog.CatalogSync

/**
 * The `Syncer` port, and the single place §1's "name the status and the cause" rule is applied.
 *
 * The split is the one that decides both the toast's colour and its duration: a status a person
 * has to act on is an error, and anything the network will fix by itself is informational.
 *
 * **It catches the domain's failures, not the transport's.** That is what makes one adapter
 * correct on both platforms. An earlier version caught Ktor's `ResponseException` and a JVM
 * `IOException`, which worked on the desktop by accident of which engine was installed: on iOS
 * the Darwin engine raises neither, so an unreachable host fell through to the unmapped branch
 * and put 400 characters of `NSURLErrorDomain` on screen — the precise opposite of §1's rule,
 * reached by obeying its letter. `S3Client` already maps both cases, and `S3HttpFailure`
 * already carries the wording §1 specifies, "so the wording cannot drift between the CLI and
 * the app". Using it is the whole fix.
 */
public class CatalogSyncer(
    private val sync: CatalogSync,
    /**
     * §4 gains a step for the app: after the shards land, fetch every pack whose `thumbs_id`
     * moved. It runs here rather than inside the domain because queueing downloads is the
     * app's business, and the CLI writes packs rather than collecting them.
     */
    private val thenFetchPacks: () -> Unit,
) : Syncer {
    override suspend fun sync(onProgress: (fetched: Int, total: Int) -> Unit): SyncOutcome =
        try {
            // Every sync rebuilds — a launch and a pull alike. It costs 1–3 s off the main thread,
            // and it is what repairs a catalog whose last rebuild never committed: the diff has
            // already recorded those deletions, so it would never ask for another.
            val report = sync.sync(alwaysRebuild = true) { fetched, total -> onProgress(fetched, total) }
            thenFetchPacks()
            SyncOutcome.Succeeded(albums = report.albums, photos = report.photos)
        } catch (failure: StorageUnreachableFailure) {
            // Nothing answered, so there is nothing to correct and nothing to log out of. §7
            // reads this as *not now* and spends an exit code on saying so; here it is the
            // difference between an informational toast and a red one.
            SyncOutcome.Failed(offline)
        } catch (failure: S3HttpFailure) {
            SyncOutcome.Failed(Notice.error("Sync failed", failure.userMessage))
        } catch (failure: PhotosFailure) {
            // Every other failure the design anticipated already composes a sentence a person
            // can act on — that is what PhotosFailure is for — so it is shown rather than
            // reworded.
            SyncOutcome.Failed(Notice.error("Sync failed", failure.message))
        } catch (failure: IOException) {
            // Not the network: the domain reads and writes shards and the merged database
            // through kotlinx-io, so a full disk or an unreadable cache arrives here.
            SyncOutcome.Failed(
                Notice.error("Sync failed", failure.message ?: "the cache could not be read or written"),
            )
        } catch (failure: Exception) {
            // §1 forbids opaque errors, and an unmapped exception is the most opaque outcome
            // there is: without this the coroutine dies silently and the screen says "running"
            // for ever. Naming the type is the least this can do while still being honest that
            // it was not anticipated.
            println("sync failed: $failure")
            SyncOutcome.Failed(
                Notice.error("Sync failed", failure.message ?: failure::class.simpleName ?: "unknown error"),
            )
        }

    private val offline = Notice.info("No network", "Showing the catalog as it was at the last sync.")
}
