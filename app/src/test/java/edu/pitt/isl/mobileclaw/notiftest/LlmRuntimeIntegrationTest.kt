package edu.pitt.isl.mobileclaw.notiftest

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Exercises the runtime factory over real loopback HTTP. All profiles and prompts are
 * synthetic; no singleton settings, installed app data or external endpoint is used.
 * runBlocking/withTimeout deliberately measure real time for blocked socket reads.
 */
class LlmRuntimeIntegrationTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun snapshot(
        target: MockWebServer = server,
        model: String = "fixture-model",
        auth: LlmAuth = LlmAuth.BEARER,
        key: String = "fixture-key",
        revision: Long = 1,
        budget: Int = 30_000
    ) = LlmSnapshot(
        LlmProfile(LlmProvider.CUSTOM, target.url("/v1/").toString(), model, key, auth,
            readTimeoutSeconds = 5, inputBudgetTokens = budget, supportsImages = false).validated(),
        revision
    )

    private fun spec(snapshot: LlmSnapshot, tools: List<Tool> = emptyList()) = AgentSpec(
        id = "runtime-fixture",
        displayName = "Runtime fixture",
        systemPrompt = "Answer the synthetic test request.",
        model = snapshot.profile.model,
        tools = tools
    )

    private val history = listOf(ChatMessage(ChatMessage.Role.USER, "Reply with test-ok."))

    private fun delta(text: String): String = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("delta", JSONObject().put("content", text))
    )).toString()

    private fun completeResponse(): MockResponse {
        val end = JSONObject().put("choices", JSONArray().put(
            JSONObject().put("delta", JSONObject()).put("finish_reason", "stop")
        ))
        return MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: " + delta("test-ok") + "\n\ndata: " + end + "\n\ndata: [DONE]\n\n")
    }

    /**
     * Send a complete first SSE event but promise more body bytes that never arrive.
     * Unlike a delayed initial response, this forces cancellation through an already
     * returned Response body. MockWebServer remains able to observe a client disconnect.
     */
    private fun stalledResponse(): MockResponse {
        val firstEvent = "data: " + delta("first") + "\n\n"
        return MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(firstEvent)
            .setHeader("Content-Length", firstEvent.toByteArray(Charsets.UTF_8).size + 10_000)
    }

    private fun request(target: MockWebServer = server): RecordedRequest =
        requireNotNull(target.takeRequest(2, TimeUnit.SECONDS)) { "Expected a loopback request." }

    @Test fun noneAuthWithEmptyKeyMakesARealRequestWithoutAuthorization() = runBlocking {
        server.enqueue(completeResponse())
        val snapshot = snapshot(auth = LlmAuth.NONE, key = "")
        val events = withTimeout(5_000) { LlmClients.chat(snapshot).run(spec(snapshot), history).toList() }
        assertEquals("test-ok", events.filterIsInstance<AgentEvent.Done>().single().message.content)
        val request = request()
        assertNull(request.getHeader("Authorization"))
        assertEquals("/v1/chat/completions", request.path)
        assertEquals(snapshot.profile.model, JSONObject(request.body.readUtf8()).getString("model"))
    }

    @Test fun clientsAndSpecsKeepTheEndpointModelAndAuthOfTheirOwnSnapshot() = runBlocking {
        val replacementServer = MockWebServer().apply { start() }
        try {
            val oldSnapshot = snapshot(model = "old-model", key = "old-fixture-key", revision = 10)
            val oldClient = LlmClients.chat(oldSnapshot)
            val oldSpec = spec(oldSnapshot)

            // The old client has been constructed before the new configuration exists.
            val newSnapshot = snapshot(replacementServer, model = "new-model", auth = LlmAuth.NONE,
                key = "", revision = 11)
            val newClient = LlmClients.chat(newSnapshot)
            server.enqueue(completeResponse())
            replacementServer.enqueue(completeResponse())

            withTimeout(5_000) {
                oldClient.run(oldSpec, history).toList()
                newClient.run(spec(newSnapshot), history).toList()
            }
            val oldRequest = request()
            val newRequest = request(replacementServer)
            assertEquals("Bearer old-fixture-key", oldRequest.getHeader("Authorization"))
            assertEquals("old-model", JSONObject(oldRequest.body.readUtf8()).getString("model"))
            assertNull(newRequest.getHeader("Authorization"))
            assertEquals("new-model", JSONObject(newRequest.body.readUtf8()).getString("model"))
            assertEquals(1, server.requestCount)
            assertEquals(1, replacementServer.requestCount)
        } finally {
            replacementServer.shutdown()
        }
    }

    @Test fun aCredentialEchoInAnHttpErrorIsRedactedBeforeExcerptingIt() = runBlocking {
        // Longer than the user-visible excerpt: truncating first would leak a key prefix.
        val key = "fixture-secret-" + "K".repeat(220)
        val body = JSONObject().put("error", JSONObject().put("message", "Invalid credential: " + key))
        server.enqueue(MockResponse().setResponseCode(400).setBody(body.toString()))
        val snapshot = snapshot(key = key)
        val events = withTimeout(5_000) { LlmClients.chat(snapshot).run(spec(snapshot), history).toList() }
        val error = events.filterIsInstance<AgentEvent.Error>().single().error
        assertEquals(AgentError.Kind.BAD_REQUEST, error.kind)
        assertTrue(error.message.contains("[redacted]"))
        assertFalse(error.message.contains(key.take(24)))
        assertFalse(error.message.contains(key.takeLast(24)))
        assertFalse(events.toString().contains(key))
        assertEquals("Bearer " + key, request().getHeader("Authorization"))
    }

    @Test fun aRedirectCannotReplayThePromptOrCredentialToAnotherServer() = runBlocking {
        val redirectTarget = MockWebServer().apply { start() }
        try {
            redirectTarget.enqueue(completeResponse())
            server.enqueue(MockResponse().setResponseCode(307)
                .setHeader("Location", redirectTarget.url("/capture").toString()))
            val snapshot = snapshot()
            val events = withTimeout(5_000) {
                LlmClients.chat(snapshot).run(spec(snapshot), history).toList()
            }
            assertEquals(AgentError.Kind.BAD_REQUEST,
                events.filterIsInstance<AgentEvent.Error>().single().error.kind)
            assertEquals(1, server.requestCount)
            assertNull(redirectTarget.takeRequest(200, TimeUnit.MILLISECONDS))
            assertEquals(0, redirectTarget.requestCount)
        } finally {
            redirectTarget.shutdown()
        }
    }

    @Test fun aCatalogThatCannotFitTheInputBudgetStopsBeforeHttpOrToolInvocation() = runBlocking {
        var invoked = false
        val tool = object : Tool {
            override val name = "fixture_reader"
            override val description = "Large fixed catalog description. ".repeat(300)
            override val parameters = JSONObject().put("type", "object")
            override suspend fun invoke(args: JSONObject): String {
                invoked = true
                return "not expected"
            }
        }
        val snapshot = snapshot(budget = 1_024)
        val events = withTimeout(5_000) {
            LlmClients.chat(snapshot).run(spec(snapshot, listOf(tool)), history).toList()
        }
        val error = events.filterIsInstance<AgentEvent.Error>().single().error
        assertEquals(AgentError.Kind.BAD_REQUEST, error.kind)
        assertTrue(error.message.contains("input budget"))
        assertFalse(invoked)
        assertEquals(0, server.requestCount)
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test fun cancellingAChatClosesItsBlockedSseReadBeforeTheReadTimeout() = runBlocking {
        server.enqueue(stalledResponse())
        val snapshot = snapshot()
        val receivedFirstToken = CompletableDeferred<Unit>()
        val events = mutableListOf<AgentEvent>()
        val turn = launch {
            LlmClients.chat(snapshot).run(spec(snapshot), history).collect { event ->
                events += event
                if (event is AgentEvent.TokenDelta) receivedFirstToken.complete(Unit)
            }
        }
        withTimeout(5_000) { receivedFirstToken.await() }
        // Give the producer a chance to block on the promised, absent next body bytes.
        delay(100)
        assertFalse(turn.isCompleted)
        withTimeout(2_000) { turn.cancelAndJoin() }
        assertTrue(turn.isCancelled)
        assertTrue(events.any { it is AgentEvent.TokenDelta })
        assertFalse(events.any { it is AgentEvent.Done || it is AgentEvent.Error })
        assertEquals(1, server.requestCount)
    }

    @Test fun cancellationClosesABlockedBodyOnASingleThreadDispatcher() = runBlocking {
        server.enqueue(stalledResponse())
        val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
        val call = http.newCall(Request.Builder().url(server.url("/blocked-body")).build())
        val bodyOpened = CompletableDeferred<Unit>()
        val bodyClosed = CompletableDeferred<Unit>()
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val reader = launch(dispatcher) {
                try {
                    call.executeCancellable().use { response ->
                        val source = requireNotNull(response.body).source()
                        assertTrue(requireNotNull(source.readUtf8Line()).startsWith("data: "))
                        assertEquals("", source.readUtf8Line())
                        bodyOpened.complete(Unit)
                        // The server advertised more bytes and deliberately sends none.
                        source.readUtf8Line()
                    }
                } catch (failure: IOException) {
                    if (currentCoroutineContext().isActive) throw failure
                } finally {
                    bodyClosed.complete(Unit)
                }
            }
            withTimeout(5_000) { bodyOpened.await() }
            delay(100)
            assertFalse(reader.isCompleted)
            withTimeout(2_000) {
                reader.cancelAndJoin()
                bodyClosed.await()
            }
            assertTrue("Cancellation must reach the actual OkHttp call", call.isCanceled())
            assertTrue(reader.isCancelled)
        }
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }
}
