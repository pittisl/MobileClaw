package edu.pitt.isl.mobileclaw.notiftest

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Explicitly opt-in, paid API acceptance; normal JVM test runs skip every case. */
class GeminiLiveAcceptanceTest {
    private lateinit var key: String

    @Before fun configure() {
        assumeTrue("Use tools/eval/run_gemini_acceptance.sh to authorize live requests",
            System.getProperty("mobileclaw.liveGemini") == "true")
        val properties = Properties().apply {
            File(requireNotNull(System.getProperty("mobileclaw.localProperties"))).inputStream().use { load(it) }
        }
        key = properties.getProperty("LLM_API_KEY", "")
        check(key.isNotBlank()) { "A Gemini development API key is required in local.properties" }
    }

    private class Chain {
        val calls = mutableListOf<String>()
        val nonce = UUID.randomUUID().toString()
        val receipt = "VERIFIED_" + UUID.randomUUID().toString().replace("-", "")
        val first = object : Tool {
            override val name = "read_challenge"
            override val description = "Read a fresh synthetic challenge. Call this first."
            override val parameters = JSONObject("""{"type":"object","properties":{}}""")
            override suspend fun invoke(args: JSONObject): String {
                check(calls.isEmpty()) { "Challenge must be read exactly once" }
                calls += name
                return JSONObject().put("challenge", nonce).toString()
            }
        }
        val second = object : Tool {
            override val name = "verify_challenge"
            override val description = "Submit the exact challenge returned by read_challenge to obtain a receipt."
            override val parameters = JSONObject("""{"type":"object","properties":{"challenge":{"type":"string"}},"required":["challenge"]}""")
            override suspend fun invoke(args: JSONObject): String {
                check(calls == listOf(first.name)) { "Verification must follow challenge read" }
                check(args.getString("challenge") == nonce) { "Model did not carry the tool result into the next call" }
                calls += name
                return JSONObject().put("receipt", receipt).toString()
            }
        }
        val tools = listOf(first, second)
        val instruction = "Call read_challenge once. Then call verify_challenge with the exact challenge it returns. " +
            "After that tool returns, reply with only its receipt value. Never invent either value."
        fun spec() = AgentSpec("live-acceptance", "Live acceptance", instruction, MODEL, tools = tools)
        fun catalog() = JSONArray().apply {
            tools.forEach { put(JSONObject().put("type", "function").put("function", JSONObject()
                .put("name", it.name).put("description", it.description).put("parameters", it.parameters))) }
        }
    }

    @Test fun productionStreamingLoopCompletesTwoDependentToolsTwice() = runBlocking {
        repeat(2) { run ->
            val chain = Chain()
            val events = withTimeout(180_000) {
                OpenAiAiClient(apiKey = key, endpoint = ENDPOINT).run(chain.spec(), listOf(
                    ChatMessage(ChatMessage.Role.USER, "Complete the synthetic challenge protocol."))).toList()
            }
            assertTrue("API errors: " + events.filterIsInstance<AgentEvent.Error>().map { it.error.kind },
                events.none { it is AgentEvent.Error })
            assertEquals(listOf(chain.first.name, chain.second.name), chain.calls)
            assertEquals(chain.calls, events.filterIsInstance<AgentEvent.ToolCall>().map { it.name })
            assertEquals(2, events.filterIsInstance<AgentEvent.ToolResult>().size)
            val done = events.filterIsInstance<AgentEvent.Done>().single()
            assertTrue(done.isComplete)
            assertEquals(chain.receipt, done.message.content.trim())
            val usage = events.filterIsInstance<AgentEvent.Usage>()
            assertEquals("Exactly three model responses are required", 3, usage.size)
            println("LIVE streaming run=${run + 1} requests=${usage.size} tools=${chain.calls.size} " +
                "prompt_tokens=${usage.sumOf { it.promptTokens }} completion_tokens=${usage.sumOf { it.completionTokens }}")
        }
    }

    @Test fun wholeBodySignaturesSurviveTwoRoundsAndMissingSignatureIsRejected() = runBlocking {
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .callTimeout(60, TimeUnit.SECONDS).build()
        try {
            val chain = Chain()
            val messages = JSONArray().put(JSONObject().put("role", "system").put("content", chain.instruction))
                .put(JSONObject().put("role", "user").put("content", "Complete the synthetic challenge protocol."))
            fun body(history: JSONArray) = ChatRequestOptions.applyTo(ENDPOINT, JSONObject()
                .put("model", MODEL).put("stream", false).put("messages", history).put("tools", chain.catalog()))
            fun request(body: JSONObject): Pair<Int, String> {
                val req = Request.Builder().url(ENDPOINT).header("Authorization", "Bearer $key")
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build()
                return client.newCall(req).execute().use { it.code to it.body!!.string() }
            }
            var signatures = 0
            for (step in 0..2) {
                val (status, response) = request(body(messages))
                assertEquals("Whole-body step $step HTTP status", 200, status)
                val turn = SseTurn()
                turn.fallbackFromWholeBody(response)
                if (step == 2) {
                    assertFalse(turn.hasToolCalls())
                    assertEquals("stop", turn.finishReason)
                    assertEquals(chain.receipt, turn.content.toString().trim())
                } else {
                    val assistant = turn.assistantTurnJson()
                    val call = assistant.getJSONArray("tool_calls").getJSONObject(0)
                    assertFalse(call.getJSONObject("extra_content").getJSONObject("google")
                        .getString("thought_signature").isBlank())
                    signatures++
                    val (id, name, args) = turn.toolCalls().single()
                    val tool = chain.tools[step]
                    assertEquals(tool.name, name)
                    val result = tool.invoke(JSONObject(args))
                    messages.put(assistant).put(JSONObject().put("role", "tool").put("name", name)
                        .put("tool_call_id", id).put("content", result))
                    if (step == 0) {
                        val broken = JSONArray(messages.toString())
                        broken.getJSONObject(2).getJSONArray("tool_calls").getJSONObject(0).remove("extra_content")
                        val (code, error) = request(body(broken))
                        assertEquals("Negative control must reject a missing signature", 400, code)
                        assertTrue("Provider rejection should identify the signature", error.contains("signature", ignoreCase = true))
                        println("LIVE negative_control missing_signature_http=$code")
                    }
                }
            }
            assertEquals(listOf(chain.first.name, chain.second.name), chain.calls)
            println("LIVE whole_body requests=3 tools=2 signatures=$signatures")
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test fun actualConnectionProbeChecksTextToolRoundTripJsonAndImage() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { interceptor ->
            val response = interceptor.proceed(interceptor.request())
            if (response.header("Content-Type").orEmpty().contains("event-stream")) {
                val turn = SseTurn()
                response.peekBody(1_048_576).string().lineSequence()
                    .filter { it.startsWith("data:") && it.substring(5).trim() != "[DONE]" }
                    .forEach { turn.onPayload(it.substring(5).trim()) }
                println("LIVE probe_response http=${response.code} finish=${turn.finishReason} " +
                    "calls=${turn.toolCalls().map { it.second }} " +
                    "marker_matches=${turn.toolCalls().map { JSONObject(it.third).optString("marker") == "MOBILECLAW_PROBE_OK" }}")
            }
            response
        }.build()
        try {
            val result = withTimeout(240_000) {
                ModelConnectionProbe(http).test(LlmProfile.preset(LlmProvider.GEMINI).copy(apiKey = key))
            }
            for ((name, check) in listOf("streaming" to result.streaming, "tool_round_trip" to result.toolCalling,
                "json" to result.structuredOutput, "image" to result.vision)) {
                println("LIVE connection_probe $name=${check.status}: ${check.detail}")
            }
            for (check in listOf(result.streaming, result.toolCalling, result.structuredOutput, result.vision)) {
                assertEquals(check.detail, ModelConnectionProbe.Status.SUPPORTED, check.status)
            }
        } finally {
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }

    companion object {
        private const val MODEL = "gemini-3.5-flash-lite"
        private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
    }
}
