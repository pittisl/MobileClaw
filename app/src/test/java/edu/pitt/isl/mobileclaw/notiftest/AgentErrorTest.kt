package edu.pitt.isl.mobileclaw.notiftest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException

/**
 * The classification rules on their own. Pure JVM — [AgentError] has no Android
 * imports for exactly this reason, the same argument as [FsPolicyTest] and
 * [HistoryBudgetTest].
 *
 * Worth testing rather than eyeballing: a misclassification is invisible. Calling a
 * missing API key a network error still produces a plausible red bubble, just one that
 * sends the user to check their wifi for a problem that lives in `local.properties` —
 * and the retry it offers fails identically every time, which is how the failure hides.
 */
class AgentErrorTest {

    // ---- the distinction this class exists for -------------------------------

    @Test
    fun `a missing key and a dead network are different errors with different remedies`() {
        val unconfigured = AgentError.notConfigured()
        val offline = AgentError.classify(
            UnknownHostException("api.example.com"), "https://api.example.com/v1"
        )

        assertEquals(AgentError.Kind.NOT_CONFIGURED, unconfigured.kind)
        assertEquals(AgentError.Kind.NETWORK, offline.kind)

        // The part a user acts on: only one of these is worth trying again.
        assertFalse("a retry cannot configure a key", unconfigured.retryable)
        assertTrue("a network can come back", offline.retryable)
    }

    @Test
    fun `a rejected key is not a missing one`() {
        val rejected = AgentError.fromHttp(401, "invalid_api_key")

        assertEquals(AgentError.Kind.AUTH, rejected.kind)
        assertFalse("the same key will be refused again", rejected.retryable)
        // The two config failures must not read alike: one means "you never set it up",
        // the other "what you set up is wrong", and they send the user to different places.
        assertFalse(
            "a rejected key must not be reported as an absent one",
            rejected.message == AgentError.notConfigured().message
        )
    }

    @Test
    fun `403 is an auth failure too`() {
        // Some gateways answer a bad key with 403 rather than 401. Classifying it as a
        // generic bad request would offer no retry and no explanation either.
        assertEquals(AgentError.Kind.AUTH, AgentError.fromHttp(403).kind)
    }

    // ---- the DNS sinkhole, which is this project's most common failure --------

    @Test
    fun `an unresolvable host is named, not summarised as offline`() {
        val error = AgentError.classify(
            UnknownHostException("Unable to resolve host"),
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
        )

        // endpoint diagnostics: this network NXDOMAINs exactly one host. "Check your connection"
        // sends the reader looking at the wifi icon; the host name identifies it.
        assertTrue(
            "the failing host is the diagnostic: ${error.message}",
            error.message.contains("generativelanguage.googleapis.com")
        )
    }

    @Test
    fun `an unparseable endpoint still classifies`() {
        // A malformed endpoint is itself a configuration error; it must not turn
        // classifying the original failure into a second failure.
        val error = AgentError.classify(UnknownHostException("nope"), "not a url")
        assertEquals(AgentError.Kind.NETWORK, error.kind)
        assertTrue(error.message.isNotBlank())
    }

    // ---- everything else -----------------------------------------------------

    @Test
    fun `transport failures are network failures`() {
        assertEquals(AgentError.Kind.NETWORK, AgentError.classify(SocketTimeoutException("timeout")).kind)
        assertEquals(AgentError.Kind.NETWORK, AgentError.classify(IOException("broken pipe")).kind)
    }

    @Test
    fun `a status that survived the retry layer is classified by its code`() {
        // withRetry rethrows the last RetryableHttpException once the attempts run out,
        // so this is the shape a 429 or a 503 arrives in after retrying failed.
        val limited = AgentError.classify(RetryableHttpException(429, "HTTP 429", retryAfterSeconds = 30))
        assertEquals(AgentError.Kind.RATE_LIMIT, limited.kind)
        assertTrue("the wait is the useful part: ${limited.message}", limited.message.contains("30"))

        assertEquals(AgentError.Kind.SERVER, AgentError.classify(RetryableHttpException(503, "HTTP 503")).kind)
    }

    @Test
    fun `a rate limit with no Retry-After still reads as one`() {
        val limited = AgentError.fromHttp(429)
        assertEquals(AgentError.Kind.RATE_LIMIT, limited.kind)
        assertTrue("no header is not a reason to invent a number", limited.retryable)
    }

    @Test
    fun `a bad request carries the provider's reason and offers no retry`() {
        val bad = AgentError.classify(HttpStatusException(400, "model \"gpt-9\" does not exist"))
        assertEquals(AgentError.Kind.BAD_REQUEST, bad.kind)
        assertTrue("the provider's own words are the diagnostic: ${bad.message}",
            bad.message.contains("gpt-9"))
        assertFalse("the same request produces the same rejection", bad.retryable)
    }

    @Test
    fun `cancellation is not a failure of the backend`() {
        assertEquals(AgentError.Kind.CANCELLED, AgentError.classify(CancellationException("stopped")).kind)
        assertTrue("the user cancelled it, so re-running is exactly the point",
            AgentError.cancelled().retryable)
    }

    @Test
    fun `an unrecognised throwable keeps its message and keeps the retry`() {
        // The unclassified tail must not silently lose an affordance it has today.
        val unknown = AgentError.classify(IllegalStateException("something odd"))
        assertEquals(AgentError.Kind.UNKNOWN, unknown.kind)
        assertEquals("something odd", unknown.message)
        assertTrue(unknown.retryable)
    }

    @Test
    fun `a throwable with no message still says something`() {
        val unknown = AgentError.classify(IllegalStateException())
        assertTrue("an empty bubble is worse than a class name", unknown.message.isNotBlank())
    }
}
