package edu.pitt.isl.mobileclaw.notiftest

import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.random.Random

/**
 * Thrown by callers when an HTTP response carries a *transient* status code
 * (408 / 429 / 5xx) so [withRetry] knows the call is worth retrying. Non-transient
 * failures (auth, bad request) should NOT be wrapped in this — they propagate
 * immediately.
 */
class RetryableHttpException(
    val code: Int,
    message: String,
    /** `Retry-After` in seconds, when the provider sent one. Survives the retry layer
     *  so that a 429 which outlasts our attempts can tell the user how long to wait
     *  instead of leaving "rate limited" open-ended. */
    val retryAfterSeconds: Int? = null
) : Exception(message)

/** HTTP status codes worth retrying: request timeout, rate limit, server errors. */
fun isTransientHttp(code: Int): Boolean = code == 408 || code == 429 || code in 500..599

/**
 * Runs [block], retrying on transient failures (network IO or
 * [RetryableHttpException]) with exponential backoff + jitter. Anything else
 * propagates on the first throw — we never retry auth errors, bad requests,
 * or a successful-but-interrupted stream (would re-bill).
 */
suspend fun <T> withRetry(
    maxAttempts: Int = 3,
    initialDelayMs: Long = 500,
    maxDelayMs: Long = 8_000,
    block: suspend () -> T
): T {
    var attempt = 0
    var delayMs = initialDelayMs
    while (true) {
        try {
            return block()
        } catch (e: Exception) {
            val transient = e is IOException || e is RetryableHttpException
            attempt++
            if (!transient || attempt >= maxAttempts) throw e
            val jitter = Random.nextLong(0, delayMs / 2 + 1)
            delay(delayMs + jitter)
            delayMs = (delayMs * 2).coerceAtMost(maxDelayMs)
        }
    }
}
