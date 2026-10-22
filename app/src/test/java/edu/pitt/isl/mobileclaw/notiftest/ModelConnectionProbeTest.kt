package edu.pitt.isl.mobileclaw.notiftest

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual loopback HTTP and production SSE reassembly, with no Android or live model. */
class ModelConnectionProbeTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun profile() = LlmProfile(
        provider = LlmProvider.CUSTOM,
        baseUrl = server.url("/v1/").toString(),
        model = "synthetic-model",
        auth = LlmAuth.NONE,
        supportsImages = false,
        readTimeoutSeconds = 5,
    )

    private fun delta(content: String) = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("delta", JSONObject().put("content", content)))).toString()

    private fun finish(reason: String) = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("delta", JSONObject()).put("finish_reason", reason))).toString()

    private fun toolFragment(name: String? = null, arguments: String): String {
        val fn = JSONObject().put("arguments", arguments)
        if (name != null) fn.put("name", name)
        return JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject()
            .put("tool_calls", JSONArray().put(JSONObject().put("index", 0)
                .put("id", "synthetic-call").put("function", fn)))))).toString()
    }

    private fun stream(vararg chunks: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(chunks.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n")

    private fun textResponse() = stream(delta("MOBILECLAW_"), delta("PROBE_OK"), finish("stop"))

    private fun toolsResponse(marker: String = "MOBILECLAW_PROBE_OK") = stream(
        // The first empty object is a real Gemini-compatible stream shape.
        toolFragment("mobileclaw_probe", "{}"),
        toolFragment(arguments = "{\"marker\":\""),
        toolFragment(arguments = marker), toolFragment(arguments = "\"}"), finish("tool_calls"),
    )

    private fun triage() = JSONObject().put("action", "do_nothing").put("priority", "low")
        .put("reasoning", "This synthetic test is idle.").put("reply_text", "")
        .put("action_title", "").put("confidence", 1.0).put("snooze_duration_minutes", JSONObject.NULL)

    private fun completion(text: String) = MockResponse().setHeader("Content-Type", "application/json")
        .setBody(JSONObject().put("choices", JSONArray().put(JSONObject().put("message",
            JSONObject().put("role", "assistant").put("content", text)))).toString())

    private fun enqueueSuccess() {
        server.enqueue(textResponse())
        server.enqueue(toolsResponse())
        server.enqueue(completion(triage().toString()))
    }

    private fun checks(result: ModelConnectionProbe.Result) = listOf(
        result.streaming, result.toolCalling, result.structuredOutput, result.vision,
    )

    @Test fun localEndpointAcceptsBlankKeyAndTestsActualFormats() = runTest {
        enqueueSuccess()
        val result = ModelConnectionProbe().test(profile())
        assertEquals(List(3) { ModelConnectionProbe.Status.SUPPORTED }, checks(result).take(3).map { it.status })
        assertEquals(ModelConnectionProbe.Status.NOT_CHECKED, result.vision.status)
        assertEquals(3, server.requestCount)
        val requests = List(3) { server.takeRequest() }
        requests.forEach {
            assertEquals("/v1/chat/completions", it.path)
            assertNull(it.getHeader("Authorization"))
        }
        val bodies = requests.map { JSONObject(it.body.readUtf8()) }
        assertTrue(bodies[0].getBoolean("stream"))
        val functions = bodies[1].getJSONArray("tools")
        assertEquals(1, functions.length())
        assertEquals("mobileclaw_probe", functions.getJSONObject(0).getJSONObject("function").getString("name"))
        assertEquals("json_object", bodies[2].getJSONObject("response_format").getString("type"))
        assertFalse(bodies[2].getBoolean("stream"))
    }

    @Test fun explicitBearerIsSentOnlyToTheEnteredEndpoint() = runTest {
        enqueueSuccess()
        val result = ModelConnectionProbe().test(profile().copy(auth = LlmAuth.BEARER, apiKey = "synthetic-secret"))
        assertEquals(ModelConnectionProbe.Status.SUPPORTED, result.streaming.status)
        repeat(3) { assertEquals("Bearer synthetic-secret", server.takeRequest().getHeader("Authorization")) }
    }

    @Test fun noAuthDropsEvenAStaleKey() = runTest {
        enqueueSuccess()
        ModelConnectionProbe().test(profile().copy(apiKey = "must-not-send"))
        repeat(3) { assertNull(server.takeRequest().getHeader("Authorization")) }
    }

    @Test fun providerErrorsCannotEchoCredentialsOrPayloadIntoDetails() = runTest {
        val secret = "probe-only-secret-do-not-display"
        repeat(3) { server.enqueue(MockResponse().setResponseCode(401)
            .setBody("Authorization: Bearer $secret; payload: private notification")) }
        val result = ModelConnectionProbe().test(profile().copy(auth = LlmAuth.BEARER, apiKey = secret))
        checks(result).forEach {
            assertFalse(it.detail.contains(secret))
            assertFalse(it.detail.contains("Authorization"))
            assertFalse(it.detail.contains("private notification"))
        }
        assertEquals(List(3) { ModelConnectionProbe.Status.FAILED }, checks(result).take(3).map { it.status })
    }

    @Test fun wrongToolArgumentsFailOnlyThatCapability() = runTest {
        server.enqueue(textResponse())
        server.enqueue(toolsResponse("incorrect"))
        server.enqueue(completion(triage().toString()))
        val result = ModelConnectionProbe().test(profile())
        assertEquals(ModelConnectionProbe.Status.SUPPORTED, result.streaming.status)
        assertEquals(ModelConnectionProbe.Status.UNSUPPORTED, result.toolCalling.status)
        assertEquals(ModelConnectionProbe.Status.SUPPORTED, result.structuredOutput.status)
    }

    @Test fun aJson200DoesNotPretendToBeAnSseStream() = runTest {
        server.enqueue(completion("MOBILECLAW_PROBE_OK"))
        server.enqueue(toolsResponse())
        server.enqueue(completion(triage().toString()))
        val result = ModelConnectionProbe().test(profile())
        assertFalse(result.streaming.status == ModelConnectionProbe.Status.SUPPORTED)
        assertEquals(ModelConnectionProbe.Status.SUPPORTED, result.toolCalling.status)
    }

    @Test fun unfinishedSseIsNotReportedSupportedOrRetried() = runTest {
        server.enqueue(stream(delta("MOBILECLAW_PROBE_OK")))
        server.enqueue(toolsResponse())
        server.enqueue(completion(triage().toString()))
        val result = ModelConnectionProbe().test(profile())
        assertEquals(ModelConnectionProbe.Status.UNSUPPORTED, result.streaming.status)
        assertEquals(3, server.requestCount)
    }

    @Test fun triageRequiresItsActualFieldsAndTypes() = runTest {
        server.enqueue(textResponse())
        server.enqueue(toolsResponse())
        server.enqueue(completion(triage().put("confidence", "1.0").toString()))
        val result = ModelConnectionProbe().test(profile())
        assertEquals(ModelConnectionProbe.Status.SUPPORTED, result.streaming.status)
        assertEquals(ModelConnectionProbe.Status.SUPPORTED, result.toolCalling.status)
        assertEquals(ModelConnectionProbe.Status.UNSUPPORTED, result.structuredOutput.status)
    }

    @Test fun validJsonPrefixWithTrailingGarbageIsRejected() = runTest {
        server.enqueue(textResponse())
        server.enqueue(toolsResponse())
        server.enqueue(completion(triage().toString() + " trailing payload"))
        assertEquals(ModelConnectionProbe.Status.UNSUPPORTED, ModelConnectionProbe().test(profile()).structuredOutput.status)
    }

    @Test fun imageCheckUsesOnlyAnEmbeddedSyntheticImage() = runTest {
        enqueueSuccess()
        server.enqueue(completion("red"))
        val result = ModelConnectionProbe().test(profile().copy(supportsImages = true))
        assertEquals(ModelConnectionProbe.Status.SUPPORTED, result.vision.status)
        repeat(3) { server.takeRequest() }
        val body = JSONObject(server.takeRequest().body.readUtf8())
        val content = body.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        val image = content.getJSONObject(1).getJSONObject("image_url").getString("url")
        assertTrue(image.startsWith("data:image/png;base64,"))
        assertTrue(image.length < 512)
        val png = Base64.getDecoder().decode(image.substringAfter(','))
        assertTrue(png.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)))
        assertEquals("IHDR", String(png, 12, 4, Charsets.US_ASCII))
        assertEquals(32, ByteBuffer.wrap(png, 16, 4).int)
        assertEquals(32, ByteBuffer.wrap(png, 20, 4).int)
        assertFalse(body.has("tools"))
    }

    @Test fun acceptingAnImageRequestAloneDoesNotProveVision() = runTest {
        enqueueSuccess()
        server.enqueue(completion("blue"))
        assertEquals(ModelConnectionProbe.Status.UNSUPPORTED,
            ModelConnectionProbe().test(profile().copy(supportsImages = true)).vision.status)
    }

    @Test fun explicitStreamOptionsRejectionRetriesOnceOnTheSameEndpoint() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody(JSONObject().put("error", JSONObject()
            .put("param", "stream_options").put("code", "unsupported_parameter")
            .put("message", "Unknown stream_options")).toString()))
        enqueueSuccess()
        val result = ModelConnectionProbe().test(profile())
        assertEquals(ModelConnectionProbe.Status.SUPPORTED, result.streaming.status)
        assertTrue(result.streaming.detail.contains("omitted"))
        assertEquals(4, server.requestCount)
        val first = server.takeRequest()
        val retry = server.takeRequest()
        assertEquals(first.path, retry.path)
        assertTrue(JSONObject(first.body.readUtf8()).has("stream_options"))
        assertFalse(JSONObject(retry.body.readUtf8()).has("stream_options"))
    }

    @Test fun unrelated400DoesNotRetryOrFallbackToAnotherModel() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody(JSONObject().put("error", JSONObject()
            .put("message", "Unknown model. stream_options was accepted.")).toString()))
        server.enqueue(toolsResponse())
        server.enqueue(completion(triage().toString()))
        val result = ModelConnectionProbe().test(profile())
        assertEquals(ModelConnectionProbe.Status.UNSUPPORTED, result.streaming.status)
        assertEquals(ModelConnectionProbe.Status.SUPPORTED, result.toolCalling.status)
        assertEquals(3, server.requestCount)
    }

    @Test fun explicitMessageOnlyStreamOptionsRejectionIsRecognized() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody(JSONObject().put("error", JSONObject()
            .put("message", "Unrecognized request argument supplied: stream_options")).toString()))
        enqueueSuccess()
        assertEquals(ModelConnectionProbe.Status.SUPPORTED, ModelConnectionProbe().test(profile()).streaming.status)
        assertEquals(4, server.requestCount)
    }

    @Test fun redirectsNeverForwardBearerOrFollowASecondEndpoint() = runTest {
        val target = MockWebServer().apply { start() }
        try {
            repeat(3) { server.enqueue(MockResponse().setResponseCode(302)
                .setHeader("Location", target.url("/other/chat/completions"))) }
            val result = ModelConnectionProbe().test(profile().copy(auth = LlmAuth.BEARER, apiKey = "synthetic-secret"))
            assertEquals(List(3) { ModelConnectionProbe.Status.FAILED }, checks(result).take(3).map { it.status })
            assertEquals(0, target.requestCount)
            assertEquals(3, server.requestCount)
        } finally { target.shutdown() }
    }

    @Test fun cancellationClosesAnOpenResponseBodyAndStopsTheRemainingChecks() = runBlocking {
        val reading = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val activeCall = AtomicReference<Call>()
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callStart(call: Call) { activeCall.set(call) }
            override fun responseBodyStart(call: Call) { reading.complete(Unit) }
            override fun canceled(call: Call) { cancelled.complete(Unit) }
        }).build()
        val firstEvent = "data: " + delta("MOBILECLAW_") + "\n\n"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(firstEvent)
            .setHeader("Content-Length", firstEvent.toByteArray(Charsets.UTF_8).size + 10_000))
        val job = launch(start = CoroutineStart.UNDISPATCHED) { ModelConnectionProbe(client).test(profile()) }
        try {
            withTimeout(5_000) { reading.await() }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertFalse(job.isCompleted)
            withTimeout(2_000) {
                job.cancelAndJoin()
                cancelled.await()
            }
            assertTrue(activeCall.get().isCanceled())
            assertEquals(1, server.requestCount)
        } finally {
            job.cancelAndJoin()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test fun invalidSettingsNeverContactTheEndpoint() = runTest {
        val result = ModelConnectionProbe().test(profile().copy(auth = LlmAuth.BEARER, apiKey = ""))
        assertEquals(ModelConnectionProbe.Status.FAILED, result.streaming.status)
        assertEquals(ModelConnectionProbe.Status.NOT_CHECKED, result.vision.status)
        assertEquals(0, server.requestCount)
    }
}
