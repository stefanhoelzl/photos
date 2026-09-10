package net.stho.photos.storage

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpRequestRetry
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.coroutines.cancellation.CancellationException
import net.stho.photos.PhotosFailure
import net.stho.photos.StorageUnreachableFailure
import kotlinx.io.IOException

/**
 * Exponential backoff with jitter, for the failures that are worth repeating.
 *
 * Not a type of our own: Ktor's retry plugin already is one, and it also honours `Retry-After`,
 * which a hand-rolled policy would have to reimplement. Installing it is the caller's job so
 * that the whole policy is visible where the client is built.
 */
public fun HttpClientConfig<*>.retryStorageFailures(
    maxAttempts: Int = 4,
    baseDelay: Duration = 200.milliseconds,
    maxDelay: Duration = 20.seconds,
) {
    install(HttpRequestRetry) {
        maxRetries = maxAttempts - 1

        // 5xx and 429 only. A 403 means the password is wrong and a 404 means the object is not
        // there; repeating either just wastes time before showing the same message. 412 is not
        // a failure at all — it comes back as a value, because the caller must re-read first.
        retryIf { _, response -> response.status.value == 429 || response.status.value >= 500 }

        // A request that never produced a response — DNS, TLS, a dropped connection.
        //
        // Not `cause is IOException`, which is what this used to say and what never once
        // matched: Ktor's curl engine raises a plain `IllegalStateException`, so every transport
        // failure skipped the retry entirely and went straight to killing the process. The
        // engines disagree on the type, so the test is what it is *not* — a failure this project
        // raised deliberately is a decision, and anything else is the transport.
        retryOnExceptionIf { _, cause -> cause !is PhotosFailure }

        // Full jitter proportional to the base delay, rather than Ktor's fixed second: it
        // spreads a thundering herd after a 503 without putting a floor under the first retry.
        exponentialDelay(
            baseDelayMs = baseDelay.inWholeMilliseconds,
            maxDelayMs = maxDelay.inWholeMilliseconds,
            randomizationMs = baseDelay.inWholeMilliseconds,
        )
    }
}

/**
 * Runs [block], and turns a transport failure into one this project owns.
 *
 * Ktor's engines do not agree on what they throw when a request never reaches a server: curl
 * raises a plain `IllegalStateException`, which is neither an `IOException` nor a
 * [PhotosFailure]. Matching on the type is therefore not something to rely on — the reliable
 * fact is *where*: anything raised while executing a request, that is not already one of ours,
 * is the transport failing.
 *
 * Without this the exception leaves §7's exit-code contract entirely and reaches
 * Kotlin/Native's uncaught handler, which aborts the process with a core dump (§1).
 */
internal suspend fun <T> reachingZone(block: suspend () -> T): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (ours: PhotosFailure) {
    throw ours
} catch (transport: Exception) {
    throw StorageUnreachableFailure(transport.reasonOnly())
}

/**
 * The half of an engine's message worth showing.
 *
 * curl composes *"Connection failed for request: CurlRequestData(url='…', method='GET', …).
 * Reason: Could not resolve hostname"* — the useful clause is the last one, and the rest is the
 * request this project just made, spelled out with its query string. §1 asks a failure to name
 * its cause; it does not ask it to quote the request back.
 */
private fun Exception.reasonOnly(): String {
    val text = message ?: return this::class.simpleName ?: "unknown transport failure"
    return text.substringAfterLast("Reason: ").trim().ifEmpty { text }
}
