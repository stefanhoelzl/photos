package net.stho.photos.desktop

import io.ktor.client.plugins.ResponseException
import java.io.IOException
import net.stho.photos.catalog.CatalogSync
import net.stho.photos.app.Notice
import net.stho.photos.app.SyncOutcome
import net.stho.photos.app.Syncer

/**
 * The `Syncer` port, and the single place §1's "name the status and the cause" rule is applied.
 *
 * The split is the one that decides both the toast's colour and its duration: a status a person
 * has to act on is an error, and anything the network will fix by itself is informational.
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
            val report = sync.sync { fetched, total -> onProgress(fetched, total) }
            thenFetchPacks()
            SyncOutcome.Succeeded(albums = report.albums, photos = report.photos)
        } catch (failure: ResponseException) {
            SyncOutcome.Failed(failure.notice())
        } catch (failure: IOException) {
            SyncOutcome.Failed(offline)
        } catch (failure: kotlinx.io.IOException) {
            // The domain reads and writes through kotlinx-io, whose IOException is its own type
            // on every target -- catching only java.io's would let a dropped connection escape.
            SyncOutcome.Failed(offline)
        } catch (failure: Exception) {
            // §1 forbids opaque errors, and an unmapped exception is the most opaque outcome
            // there is: without this the coroutine dies silently and the screen says "running"
            // for ever. Naming the type is the least this can do while still being honest that
            // it was not anticipated.
            System.err.println("sync failed: $failure")
            SyncOutcome.Failed(
                Notice.error("Sync failed", failure.message ?: failure::class.simpleName ?: "unknown error"),
            )
        }

    private val offline = Notice.info("No network", "Showing the catalog as it was at the last sync.")

    private fun ResponseException.notice(): Notice {
        val status = response.status
        val remedy = when (status.value) {
            401, 403 -> "Log out and check the password."
            404 -> "The zone name in the storage URL does not exist."
            else -> "The storage zone rejected the request."
        }
        return Notice.error("Sync failed: ${status.value} ${status.description}", remedy)
    }
}
