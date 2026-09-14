package net.stho.photos.app

import io.ktor.client.HttpClient
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * §8's `BackgroundUploader` port: PUTs of files to pre-signed URLs.
 *
 * The one HTTP path that does not go through `S3Client`, because Ktor's Darwin engine cannot
 * drive a background `URLSession`. It never holds the password — every transfer carries its own
 * signed URL — which is what lets the phone's session outlive the app.
 *
 * It keeps no record of what finished. [Uploads] asks the zone instead, so a relaunch that missed
 * every event still knows exactly what landed.
 */
public interface BackgroundUploader {
    public val events: SharedFlow<TransferEvent>

    /** Keys this uploader is still transferring, or holding to transfer — `getAllTasks` on iOS. */
    public suspend fun inFlight(): Set<String>

    /** Queues these transfers. One already queued under the same key is left as it is. */
    public suspend fun enqueue(transfers: List<Transfer>)

    public suspend fun cancel(keys: Set<String>)
}

public data class Transfer(val key: String, val url: String, val file: Path)

public sealed interface TransferEvent {
    public val key: String

    public data class Sent(override val key: String, val bytes: Long) : TransferEvent

    public data class Finished(override val key: String) : TransferEvent

    /** [status] is null when nothing answered at all. */
    public data class Failed(override val key: String, val status: Int?, val reason: String) : TransferEvent
}

/**
 * The uploader everywhere but the phone: one PUT at a time, in the foreground.
 *
 * One at a time because §9 measured more concurrent uploads as slightly *slower* on a domestic
 * link, and because the order transfers were queued in is then the order they land.
 */
public class ForegroundUploader(private val http: HttpClient, scope: CoroutineScope) : BackgroundUploader {
    private val _events = MutableSharedFlow<TransferEvent>(extraBufferCapacity = 1024)
    override val events: SharedFlow<TransferEvent> = _events.asSharedFlow()

    private val mutex = Mutex()
    private val queued = LinkedHashMap<String, Transfer>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            while (true) {
                wake.receive()
                drain()
            }
        }
    }

    override suspend fun inFlight(): Set<String> = mutex.withLock { queued.keys.toSet() }

    override suspend fun enqueue(transfers: List<Transfer>) {
        mutex.withLock { for (transfer in transfers) if (transfer.key !in queued) queued[transfer.key] = transfer }
        wake.trySend(Unit)
    }

    override suspend fun cancel(keys: Set<String>) {
        mutex.withLock { keys.forEach(queued::remove) }
    }

    private suspend fun drain() {
        while (true) {
            val next = mutex.withLock { queued.values.firstOrNull() } ?: return
            val outcome = send(next)
            // Cancelled while it was sending: nobody is waiting on its outcome any more.
            val wanted = mutex.withLock { queued.remove(next.key) != null }
            if (wanted) _events.emit(outcome)
        }
    }

    private suspend fun send(transfer: Transfer): TransferEvent = try {
        val response = http.put(transfer.url) { setBody(FileBody(transfer.file)) }
        if (response.status.value in 200..299) {
            TransferEvent.Finished(transfer.key)
        } else {
            TransferEvent.Failed(transfer.key, response.status.value, response.bodyAsText().take(200))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        TransferEvent.Failed(transfer.key, null, failure.message ?: failure::class.simpleName ?: "network error")
    }
}

/** A file streamed with its length declared, as `S3Client` streams one. */
private class FileBody(private val path: Path) : OutgoingContent.WriteChannelContent() {
    override val contentLength: Long? = SystemFileSystem.metadataOrNull(path)?.size

    override suspend fun writeTo(channel: ByteWriteChannel) {
        SystemFileSystem.source(path).buffered().use { source ->
            val chunk = ByteArray(1 shl 16)
            while (true) {
                val read = source.readAtMostTo(chunk, 0, chunk.size)
                if (read <= 0) break
                channel.writeFully(chunk, 0, read)
            }
        }
        channel.flushAndClose()
    }
}
