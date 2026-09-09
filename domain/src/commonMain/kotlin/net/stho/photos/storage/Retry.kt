package net.stho.photos.storage

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpRequestRetry
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
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
        retryOnExceptionIf { _, cause -> cause is IOException }

        // Full jitter proportional to the base delay, rather than Ktor's fixed second: it
        // spreads a thundering herd after a 503 without putting a floor under the first retry.
        exponentialDelay(
            baseDelayMs = baseDelay.inWholeMilliseconds,
            maxDelayMs = maxDelay.inWholeMilliseconds,
            randomizationMs = baseDelay.inWholeMilliseconds,
        )
    }
}
