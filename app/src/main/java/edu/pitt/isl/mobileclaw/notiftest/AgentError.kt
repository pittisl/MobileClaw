package edu.pitt.isl.mobileclaw.notiftest

import java.io.IOException
import java.net.URI
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException

/**
 * A request the server refused with a non-transient status. The sibling of
 * [RetryableHttpException]: that one means "worth another attempt", this one means
 * "the status is the answer". Both exist so the status code survives as far as
 * [AgentError.classify] instead of being flattened into an `IllegalStateException`
 * message that only a human can read.
 */
class HttpStatusException(val code: Int, val body: String) : Exception("HTTP $code: $body")

/**
 * Why a turn failed, in terms the user can act on.
 *
 * Missing configuration needs a setup change; a network interruption may be temporary.
 * [kind] determines [retryable] so the UI offers recovery only when another attempt
 * could succeed.
 *
 * Pure JVM, with no Android imports. [ReasoningModule] and [OpenAiAiClient] share the
 * classifier so triage and chat report the same failure consistently.
 */
data class AgentError(val kind: Kind, val message: String) {

    enum class Kind {
        /** Incomplete model settings. The request was never attempted. */
        NOT_CONFIGURED,
        /** A key was sent and rejected: wrong, expired, or not valid for this endpoint. */
        AUTH,
        RATE_LIMIT,
        NETWORK,
        SERVER,
        BAD_REQUEST,
        CANCELLED,
        /** Classification failed. Keeps the old behaviour for the unrecognised tail. */
        UNKNOWN
    }

    /**
     * Whether offering the user a Retry is honest.
     *
     * [Kind.NOT_CONFIGURED] and [Kind.AUTH] are false because nothing about the next
     * attempt would differ — the fix is somewhere else entirely, and a retry that
     * re-fails identically teaches the user the button does nothing. [Kind.BAD_REQUEST]
     * is false for the same reason: the same request produces the same rejection.
     * [Kind.UNKNOWN] stays true deliberately — we don't know, and today's behaviour is
     * to offer a retry, so an unclassified failure should not silently lose one.
     */
    val retryable: Boolean
        get() = when (kind) {
            Kind.NOT_CONFIGURED, Kind.AUTH, Kind.BAD_REQUEST -> false
            Kind.RATE_LIMIT, Kind.NETWORK, Kind.SERVER, Kind.CANCELLED, Kind.UNKNOWN -> true
        }

    companion object {

        /** Missing model settings are corrected in the app, without rebuilding. */
        fun notConfigured() = AgentError(
            Kind.NOT_CONFIGURED,
            "Configure a model service in Settings > Model service. " +
                "Choose a provider, enter its model and connection details, then save."
        )

        fun cancelled() = AgentError(Kind.CANCELLED, "Request cancelled.")

        /**
         * Maps a status code to a kind. [retryAfterSeconds] comes from the `Retry-After`
         * header when the provider sent one — the user is otherwise left guessing how
         * long "rate limited" lasts.
         */
        fun fromHttp(code: Int, body: String = "", retryAfterSeconds: Int? = null): AgentError = when {
            code == 401 || code == 403 -> AgentError(
                Kind.AUTH,
                "The LLM API key was rejected (HTTP $code). It may be invalid, expired, " +
                    "or not valid for this endpoint."
            )
            code == 429 -> AgentError(
                Kind.RATE_LIMIT,
                if (retryAfterSeconds != null) {
                    "Rate limited by the provider — try again in $retryAfterSeconds seconds."
                } else {
                    "Rate limited by the provider. Try again shortly."
                }
            )
            code in 500..599 -> AgentError(
                Kind.SERVER,
                "The provider returned HTTP $code and did not recover after retrying."
            )
            else -> AgentError(
                Kind.BAD_REQUEST,
                "The provider rejected the request (HTTP $code)." + excerpt(body)
            )
        }

        /**
         * Classifies whatever the transport threw. [endpoint], when known, turns the
         * least readable failure on this network into the most readable one: a DNS
         * sinkhole surfaces as `UnknownHostException` with a message that reads like
         * the phone is offline, when the actual fact — *this one host does not
         * resolve* — is what identifies it.
         */
        fun classify(t: Throwable, endpoint: String? = null): AgentError = when (t) {
            is CancellationException -> cancelled()
            is HttpStatusException -> fromHttp(t.code, t.body)
            // Survived the retry layer, so the transient status is now the final answer.
            is RetryableHttpException -> fromHttp(t.code, retryAfterSeconds = t.retryAfterSeconds)
            is UnknownHostException -> AgentError(
                Kind.NETWORK,
                hostOf(endpoint)?.let { "Can't resolve $it — the endpoint's DNS lookup failed." }
                    ?: "Can't resolve the endpoint's host — the DNS lookup failed."
            )
            is IOException -> AgentError(
                Kind.NETWORK,
                "Couldn't reach the LLM endpoint: ${t.message ?: t.javaClass.simpleName}"
            )
            else -> AgentError(Kind.UNKNOWN, t.message ?: t.javaClass.simpleName)
        }

        /** Host of [url], or null if it isn't parseable — a bad endpoint is itself a
         *  configuration error and shouldn't turn classification into a second failure. */
        private fun hostOf(url: String?): String? =
            url?.let { runCatching { URI(it).host }.getOrNull() }?.takeIf { it.isNotBlank() }

        /** Providers put the useful part of a rejection first, and the rest is noise
         *  in a chat bubble. */
        private fun excerpt(body: String): String =
            body.trim().takeIf { it.isNotEmpty() }?.let { " ${it.take(160)}" } ?: ""
    }
}
