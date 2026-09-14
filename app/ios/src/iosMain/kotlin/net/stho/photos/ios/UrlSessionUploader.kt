@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package net.stho.photos.ios

import kotlin.coroutines.resume
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import net.stho.photos.app.BackgroundUploader
import net.stho.photos.app.Transfer
import net.stho.photos.app.TransferEvent
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLErrorCancelled
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionTaskDelegateProtocol
import platform.Foundation.NSURLSessionTaskStateRunning
import platform.Foundation.NSURLSessionTaskStateSuspended
import platform.Foundation.setHTTPMethod
import platform.darwin.NSObject

/**
 * §8's transfer on the phone: a background `URLSession`, which survives the app being
 * backgrounded, the phone locked, and a crash.
 *
 * One object for the process, not one per session: iOS allows a single background session per
 * identifier, and a log-out followed by a set-up would otherwise try to create a second. Each task
 * carries its object key as `taskDescription`, which is how a relaunch recognises its own
 * transfers in `getAllTasks` — and every URL is pre-signed, so none of them needs the password.
 *
 * A user force-quit cancels every background transfer and iOS will not relaunch the app for it.
 * That is why nothing here is trusted to have finished anything: `Uploads` asks the zone.
 */
internal object UrlSessionUploader : BackgroundUploader {
    private const val IDENTIFIER = "net.stho.photos.upload"

    private val _events = MutableSharedFlow<TransferEvent>(extraBufferCapacity = 1024)
    override val events: SharedFlow<TransferEvent> = _events.asSharedFlow()

    private val delegate = Delegate()

    private val session: NSURLSession by lazy {
        val configuration = NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier(IDENTIFIER).apply {
            allowsCellularAccess = true
            sessionSendsLaunchEvents = true
            discretionary = false
        }
        NSURLSession.sessionWithConfiguration(configuration, delegate, delegateQueue = null)
    }

    override suspend fun inFlight(): Set<String> = tasks().mapNotNull { it.taskDescription }.toSet()

    override suspend fun enqueue(transfers: List<Transfer>) {
        val flying = inFlight()
        for (transfer in transfers) {
            if (transfer.key in flying) continue
            val url = NSURL.URLWithString(transfer.url) ?: continue
            val request = NSMutableURLRequest.requestWithURL(url).apply { setHTTPMethod("PUT") }
            // From a file, as a background session requires; its length is sent with it.
            session.uploadTaskWithRequest(request, fromFile = NSURL.fileURLWithPath(transfer.file.toString())).apply {
                taskDescription = transfer.key
                resume()
            }
        }
    }

    override suspend fun cancel(keys: Set<String>) {
        tasks().filter { it.taskDescription in keys }.forEach { it.cancel() }
    }

    private suspend fun tasks(): List<NSURLSessionTask> = suspendCancellableCoroutine { continuation ->
        session.getAllTasksWithCompletionHandler { tasks ->
            continuation.resume(
                tasks.orEmpty().filterIsInstance<NSURLSessionTask>()
                    .filter { it.state == NSURLSessionTaskStateRunning || it.state == NSURLSessionTaskStateSuspended },
            )
        }
    }

    private class Delegate : NSObject(), NSURLSessionTaskDelegateProtocol {
        override fun URLSession(
            session: NSURLSession,
            task: NSURLSessionTask,
            didSendBodyData: Long,
            totalBytesSent: Long,
            totalBytesExpectedToSend: Long,
        ) {
            val key = task.taskDescription ?: return
            _events.tryEmit(TransferEvent.Sent(key, totalBytesSent))
        }

        override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
            val key = task.taskDescription ?: return
            // A cancel was asked for; nobody waits on its outcome.
            if (didCompleteWithError?.code == NSURLErrorCancelled) return
            val status = (task.response as? NSHTTPURLResponse)?.statusCode?.toInt()
            _events.tryEmit(
                when {
                    didCompleteWithError != null -> TransferEvent.Failed(key, null, didCompleteWithError.localizedDescription)
                    status != null && status in 200..299 -> TransferEvent.Finished(key)
                    else -> TransferEvent.Failed(key, status, "HTTP $status")
                },
            )
        }
    }
}
