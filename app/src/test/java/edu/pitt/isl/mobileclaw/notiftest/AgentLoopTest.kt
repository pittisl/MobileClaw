package edu.pitt.isl.mobileclaw.notiftest

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drives the **real** [OpenAiAiClient] against canned SSE transcripts served over a
 * loopback socket, so the agent loop — stream, reassemble, call tools, feed results
 * back, loop again — is exercised end to end with no network and no API key.
 *
 * This needs no production seam: `endpoint` is already a constructor parameter, so
 * pointing it at a [MockWebServer] is the whole trick. What it buys is the ability
 * to script failures a live provider will never reproduce on demand: a stream that
 * dies halfway, a model that asks for tools forever, a 503 followed by success.
 *
 * Everything here is ordinary JVM code (OkHttp, a real `org.json`); the only Android
 * dependency is `android.util.Log`, covered by `unitTests.isReturnDefaultValues`.
 */
class AgentLoopTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ---- fixtures -----------------------------------------------------------

    /** Wraps payloads as an SSE body, terminated the way a provider terminates one. */
    private fun sse(vararg payloads: String) =
        payloads.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"

    private fun MockWebServer.enqueueSse(vararg payloads: String) = enqueue(
        MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse(*payloads))
    )

    private fun delta(content: String) = JSONObject()
        .put("choices", JSONArray().put(
            JSONObject().put("delta", JSONObject().put("content", content))
        )).toString()

    private fun finish(reason: String) = JSONObject()
        .put("choices", JSONArray().put(
            JSONObject().put("delta", JSONObject()).put("finish_reason", reason)
        )).toString()

    /**
     * One fragment of a streamed tool call. Providers split these across chunks —
     * id and name in the first, arguments dribbling out over several more — which is
     * exactly the reassembly that has no other coverage above the parser level.
     */
    private fun toolFrag(index: Int = 0, id: String? = null, name: String? = null, args: String? = null): String {
        val fn = JSONObject()
        if (name != null) fn.put("name", name)
        if (args != null) fn.put("arguments", args)
        val tc = JSONObject().put("index", index)
        if (id != null) tc.put("id", id)
        if (fn.length() > 0) tc.put("function", fn)
        return JSONObject().put("choices", JSONArray().put(
            JSONObject().put("delta", JSONObject().put("tool_calls", JSONArray().put(tc)))
        )).toString()
    }

    /** A chunk carrying only usage — the shape `stream_options.include_usage` produces. */
    private fun usageChunk(prompt: Int, completion: Int) = JSONObject()
        .put("choices", JSONArray())
        .put("usage", JSONObject()
            .put("prompt_tokens", prompt)
            .put("completion_tokens", completion)
            .put("total_tokens", prompt + completion))
        .toString()

    private fun client(
        budgetTokens: Int = HistoryBudget.DEFAULT_BUDGET_TOKENS,
        toolBudgetTokens: Int = ToolOutputBudget.DEFAULT_BUDGET_TOKENS
    ) = OpenAiAiClient(
        apiKey = "test-key",
        endpoint = server.url("/v1/chat/completions").toString(),
        budgetTokens = budgetTokens,
        toolBudgetTokens = toolBudgetTokens
    )

    private fun spec(vararg tools: Tool) = AgentSpec(
        id = "test-agent",
        displayName = "Test Agent",
        systemPrompt = "You are a test.",
        model = "test-model",
        tools = tools.toList()
    )

    private val history = listOf(ChatMessage(role = ChatMessage.Role.USER, content = "what's urgent?"))

    /** Records every call so the test can assert the loop actually invoked it. */
    private class RecordingTool(
        override val name: String = "read_notifications",
        private val result: String = "1 notification: Mom called twice"
    ) : Tool {
        val calls = mutableListOf<JSONObject>()
        override val description = "Reads notifications."
        override val parameters = JSONObject("""{"type":"object","properties":{}}""")
        override suspend fun invoke(args: JSONObject): String {
            calls += args
            return result
        }
    }

    private fun bodyOf(request: RecordedRequest) = JSONObject(request.body.readUtf8())

    private fun messagesOf(request: RecordedRequest): List<JSONObject> {
        val arr = bodyOf(request).getJSONArray("messages")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    private class DeferredTool(private val receipt: ToolInvocation) : Tool {
        override val name = "call_app_function"
        override val description = "A confirmed write."
        override val parameters = JSONObject("""{"type":"object"}""")
        override val requiresConfirmationReceipt = true
        var calls = 0
        override suspend fun invoke(args: JSONObject) = error("The loop must preserve the typed receipt")
        override suspend fun invokeWithReceipt(args: JSONObject): ToolInvocation { calls++; return receipt }
    }

    @Test fun `deferred write suppresses premature streamed success and stops before model summary`() = runTest {
        val tool = DeferredTool(ToolInvocation.AwaitingConfirmation("addTask {title: Demo}", "waiting"))
        server.enqueueSse(delta("I've added the task."),
            toolFrag(id = "write1", name = tool.name, args = "{}"),
            delta(" Successfully saved!"), finish("tool_calls"), usageChunk(12, 4))
        server.enqueueSse(delta("I've added it successfully."), finish("stop"))
        val events = client().run(spec(tool), history).toList()
        assertEquals(1, tool.calls)
        assertEquals("No second request asking the model to invent success", 1, server.requestCount)
        assertTrue("No false prose may flash before Done", events.filterIsInstance<AgentEvent.TokenDelta>().isEmpty())
        val final = events.filterIsInstance<AgentEvent.Done>().single().message
        assertTrue(final.content.startsWith("Waiting for confirmation."))
        assertFalse(final.content.contains("I've added"))
        assertTrue(final.actions.isEmpty())
        assertEquals(12, events.filterIsInstance<AgentEvent.Usage>().single().promptTokens)
    }

    @Test fun `rejected write cannot become a model success summary`() = runTest {
        val tool = DeferredTool(ToolInvocation.NotExecuted("Bridge unavailable"))
        server.enqueueSse(delta("Added!"), toolFrag(id = "write1", name = tool.name, args = "{}"), finish("tool_calls"))
        server.enqueueSse(delta("Added despite the failed tool."), finish("stop"))
        val events = client().run(spec(tool), history).toList()
        assertTrue(events.filterIsInstance<AgentEvent.TokenDelta>().isEmpty())
        assertEquals("Not executed: Bridge unavailable", events.filterIsInstance<AgentEvent.Done>().single().message.content)
        assertEquals(1, server.requestCount)
    }

    @Test fun `malformed deferred tool arguments fail closed without success prose`() = runTest {
        val tool = DeferredTool(ToolInvocation.AwaitingConfirmation("never", "never"))
        server.enqueueSse(delta("Saved!"), toolFrag(id = "write1", name = tool.name, args = "[bad"), finish("tool_calls"))
        server.enqueueSse(delta("Saved despite invalid arguments."), finish("stop"))
        val events = client().run(spec(tool), history).toList()
        assertEquals(0, tool.calls)
        assertTrue(events.filterIsInstance<AgentEvent.TokenDelta>().isEmpty())
        assertTrue(events.filterIsInstance<AgentEvent.Done>().single().message.content.startsWith("Not executed:"))
    }

    @Test fun `read answer with appfunctions available still arrives without a receipt`() = runTest {
        val tool = DeferredTool(ToolInvocation.NotExecuted("unused"))
        server.enqueueSse(delta("A read-only answer."), finish("stop"))
        val events = client().run(spec(tool), history).toList()
        assertEquals("A read-only answer.", events.filterIsInstance<AgentEvent.TokenDelta>().joinToString("") { it.text })
        assertEquals("A read-only answer.", events.filterIsInstance<AgentEvent.Done>().single().message.content)
        assertEquals(0, tool.calls)
    }

    /** Captures the turn scope a tool is invoked under — see [AgentTurn]. */
    private class TurnProbingTool(
        override val name: String = "probe_turn"
    ) : Tool {
        var seen: AgentTurn? = null
        var sawEvidenceFor: String? = null
        override val description = "Reports the turn it ran in."
        override val parameters = JSONObject("""{"type":"object","properties":{}}""")
        override suspend fun invoke(args: JSONObject): String {
            seen = kotlin.coroutines.coroutineContext[AgentTurn]
            return "ok"
        }
    }

    // ---- the tool round-trip ------------------------------------------------

    /**
     * Gemini's OpenAI-compatible endpoint sometimes opens a call with an `arguments` fragment
     * that is exactly `{}` and then streams the real object. Concatenated, org.json parses
     * `{}{"limit":3}` as `{}`, losing the real arguments. Replace the placeholder
     * instead of appending to it.
     */
    @Test
    fun `an empty-object placeholder fragment is replaced by the arguments that follow`() = runTest {
        val tool = RecordingTool()
        server.enqueueSse(
            toolFrag(id = "call_1", name = "read_notifications", args = "{}"),
            toolFrag(args = """{"limit":"""),
            toolFrag(args = """3}"""),
            finish("tool_calls")
        )
        server.enqueueSse(delta("done"), finish("stop"))

        client().run(spec(tool), history).toList()

        assertEquals(1, tool.calls.size)
        assertEquals("the placeholder did not swallow the real arguments", 3, tool.calls[0].getInt("limit"))
        // And what is echoed back to the model is the real object too, not "{}{...}".
        server.takeRequest()
        val assistantTurn = messagesOf(server.takeRequest()).single { it.optString("role") == "assistant" }
        assertEquals("""{"limit":3}""",
            assistantTurn.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("arguments"))
    }

    @Test
    fun `a tool call split across chunks is reassembled, invoked, and fed back`() = runTest {
        val tool = RecordingTool()
        server.enqueueSse(
            toolFrag(id = "call_1", name = "read_notifications"),
            toolFrag(args = """{"lim"""),      // arguments arrive in pieces …
            toolFrag(args = """it":3}"""),     // … and only mean anything concatenated
            finish("tool_calls")
        )
        server.enqueueSse(delta("Mom called"), delta(" twice."), finish("stop"))

        val events = client().run(spec(tool), history).toList()

        assertEquals("the tool ran exactly once", 1, tool.calls.size)
        assertEquals("fragments reassemble into valid JSON args", 3, tool.calls[0].getInt("limit"))
        assertEquals("one round-trip per turn, two turns", 2, server.requestCount)

        val done = events.filterIsInstance<AgentEvent.Done>().single()
        assertEquals("Mom called twice.", done.message.content)
        assertTrue("the tool call is observable to the UI layer",
            events.any { it is AgentEvent.ToolCall && it.name == "read_notifications" })
        assertTrue("so is its result",
            events.any { it is AgentEvent.ToolResult && it.result.contains("Mom called twice") })

        // The point of the second request: the model must see what the tool returned,
        // attached to the call it made.
        server.takeRequest()
        val second = messagesOf(server.takeRequest())
        val toolTurn = second.single { it.optString("role") == "tool" }
        assertEquals("call_1", toolTurn.getString("tool_call_id"))
        assertTrue(toolTurn.getString("content").contains("Mom called twice"))
        val assistantTurn = second.single { it.optString("role") == "assistant" }
        assertEquals("call_1",
            assistantTurn.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
    }

    @Test
    fun `an unknown tool name comes back as text the model can recover from`() = runTest {
        // The loop must not crash on a hallucinated tool name — the error is prompt
        // surface, fed back so the model can correct itself.
        server.enqueueSse(
            toolFrag(id = "call_x", name = "no_such_tool", args = "{}"),
            finish("tool_calls")
        )
        server.enqueueSse(delta("Sorry, I can't do that."), finish("stop"))

        val done = client().run(spec(RecordingTool()), history).toList()
            .filterIsInstance<AgentEvent.Done>().single()

        assertEquals("Sorry, I can't do that.", done.message.content)
        server.takeRequest()
        val toolTurn = messagesOf(server.takeRequest()).single { it.optString("role") == "tool" }
        assertTrue(toolTurn.getString("content").contains("unknown tool"))
    }

    @Test
    fun `the tool loop stops instead of spinning when the model never settles`() = runTest {
        // A model that asks for a tool every single turn. Without the iteration cap
        // this is an unbounded, billable loop.
        val tool = RecordingTool()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse(
                    toolFrag(id = "call_n", name = "read_notifications", args = "{}"),
                    finish("tool_calls")
                ))
        }

        val events = client().run(spec(tool), history).toList()

        assertEquals("five tool rounds, then the sixth request breaks the loop", 6, server.requestCount)
        assertEquals(5, tool.calls.size)
        assertTrue("the turn still terminates", events.last() is AgentEvent.Done)

        // Running out of tool steps is not a failure, and must not be reported as one:
        // the old code emitted Done("") here, which finalMessage turned into a red
        // "(no response)" bubble offering a retry that would loop identically — and,
        // being an error, was never persisted.
        val done = events.filterIsInstance<AgentEvent.Done>().single()
        assertFalse("a capped turn is not an error", done.message.isError)
        assertTrue("it says what actually happened: ${done.message.content}",
            done.message.content.contains("5 tool steps"))
    }

    @Test
    fun `a capped turn keeps whatever the model did manage to say`() = runTest {
        // A model can stream prose *and* ask for another tool. That prose is a real
        // partial answer; discarding it because the loop ran out of steps would throw
        // away the only thing the user can actually use.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse(
                    delta("Checking your calendar. "),
                    toolFrag(id = "call_n", name = "read_notifications", args = "{}"),
                    finish("tool_calls")
                ))
        }

        val done = client().run(spec(RecordingTool()), history).toList()
            .filterIsInstance<AgentEvent.Done>().single()

        assertFalse(done.message.isError)
        assertTrue("the partial answer survives: ${done.message.content}",
            done.message.content.startsWith("Checking your calendar."))
        assertTrue("and it explains why it stopped", done.message.content.contains("5 tool steps"))
    }

    @Test
    fun `tool output is bounded within one turn, without orphaning a tool_call_id`() = runTest {
        // The one path HistoryBudget doesn't reach: it runs once, before the
        // loop, and everything the loop appends after that is re-sent on every
        // iteration, uncharged. Two 64 KB shell dumps in one turn is the realistic case.
        val big = "y".repeat(4_000) // ~1000 tokens by the estimator
        val tool = RecordingTool(result = big)
        server.enqueueSse(toolFrag(id = "call_1", name = "read_notifications", args = "{}"), finish("tool_calls"))
        server.enqueueSse(toolFrag(id = "call_2", name = "read_notifications", args = "{}"), finish("tool_calls"))
        server.enqueueSse(delta("Done."), finish("stop"))

        val events = client(toolBudgetTokens = 1_200).run(spec(tool), history).toList()

        assertTrue("the UI still sees the whole result — the budget is about what is re-sent",
            events.filterIsInstance<AgentEvent.ToolResult>().all { it.result.length == big.length })

        server.takeRequest()
        server.takeRequest()
        val toolTurns = messagesOf(server.takeRequest()).filter { it.optString("role") == "tool" }

        // Evicting the *message* rather than its content would leave the assistant turn
        // referring to a tool_call_id with no result — a malformed request most
        // providers reject outright, which is why the content is replaced in place.
        assertEquals("both calls still have a reply", 2, toolTurns.size)
        assertEquals(listOf("call_1", "call_2"), toolTurns.map { it.getString("tool_call_id") })

        assertTrue("the older result was summarised: ${toolTurns[0].getString("content")}",
            toolTurns[0].getString("content").contains("omitted"))
        assertEquals("and the newest, which the model is answering from, is whole",
            big, toolTurns[1].getString("content"))
    }

    // ---- retry rules: the ones that cost money when they're wrong ------------

    @Test
    fun `a stream that dies mid-flight is never retried`() = runTest {
        // Retry wraps opening the stream only. Re-issuing a request whose tokens were
        // already billed would pay for them twice and duplicate output — the rule
        // Retry.kt exists to state, and the one nothing would notice being broken.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse(delta("Mom cal"), delta("led tw"), delta("ice."), finish("stop")))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )

        val seen = mutableListOf<AgentEvent>()
        client().run(spec(), history).collect { seen += it }

        assertEquals("the broken turn was not re-issued", 1, server.requestCount)
        assertTrue("tokens were already delivered, so this really is mid-stream",
            seen.count { it is AgentEvent.TokenDelta } >= 1)
        // The subtle part: this is a ProtocolException, i.e. an IOException — exactly
        // what withRetry counts as transient and would happily retry. It isn't retried
        // only because withRetry wraps opening the stream and nothing after it. Move
        // the read inside that block and this test is what catches the double bill.
        val error = seen.filterIsInstance<AgentEvent.Error>().single().error
        assertEquals("a broken socket is a network failure", AgentError.Kind.NETWORK, error.kind)
        assertTrue("and the user is allowed to try again", error.retryable)
    }

    @Test
    fun `a transient server error is retried, an auth error is not`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("overloaded"))
        server.enqueueSse(delta("Back online."), finish("stop"))

        val done = client().run(spec(), history).toList()
            .filterIsInstance<AgentEvent.Done>().single()
        assertEquals("Back online.", done.message.content)
        assertEquals("503 is worth a second attempt", 2, server.requestCount)

        val authServer = MockWebServer().apply { start() }
        try {
            authServer.enqueue(MockResponse().setResponseCode(401).setBody("bad key"))
            val error = OpenAiAiClient(apiKey = "test-key", endpoint = authServer.url("/v1").toString())
                .run(spec(), history).toList()
                .filterIsInstance<AgentEvent.Error>().single().error
            assertEquals("401 is the key being refused, not the network",
                AgentError.Kind.AUTH, error.kind)
            assertFalse("and a Retry chip would re-fail identically", error.retryable)
            assertEquals("retrying a bad key just burns time", 1, authServer.requestCount)
        } finally {
            authServer.shutdown()
        }
    }

    @Test
    fun `a missing key never reaches the network and offers no retry`() = runTest {
        // Missing configuration is not a transient connection failure and must not offer
        // an ineffective Retry action.
        val error = OpenAiAiClient(apiKey = "", endpoint = server.url("/v1").toString())
            .run(spec(), history).toList()
            .filterIsInstance<AgentEvent.Error>().single().error

        assertEquals(AgentError.Kind.NOT_CONFIGURED, error.kind)
        assertFalse("nothing about the next attempt would differ", error.retryable)
        assertEquals("no key means no request, not a request that fails", 0, server.requestCount)
    }

    @Test
    fun `a rate limit that outlives the retries says how long to wait`() = runTest {
        // 429 is transient, so it is retried; what matters is the answer when the
        // retries run out. "Rate limited" with no number leaves the user guessing,
        // which is the same shape of unhelpfulness as an unclassified error.
        repeat(3) {
            server.enqueue(
                MockResponse().setResponseCode(429).setHeader("Retry-After", "42").setBody("slow down")
            )
        }

        val error = client().run(spec(), history).toList()
            .filterIsInstance<AgentEvent.Error>().single().error

        assertEquals(AgentError.Kind.RATE_LIMIT, error.kind)
        assertTrue("the wait is worth surfacing: ${error.message}", error.message.contains("42"))
        assertTrue("waiting is exactly what a retry is for", error.retryable)
        assertEquals("three attempts, then the answer", 3, server.requestCount)
    }

    // ---- shape of the final message ----------------------------------------

    @Test
    fun `the trailing ACTIONS sentinel becomes chips, not visible text`() = runTest {
        server.enqueueSse(
            delta("**Mom** called twice.\n"),
            delta("$ACTIONS_SENTINEL Call back "),
            delta("| Text her | Ignore"),
            finish("stop")
        )

        val done = client().run(spec(), history).toList()
            .filterIsInstance<AgentEvent.Done>().single()

        assertEquals("**Mom** called twice.", done.message.content)
        assertEquals(listOf("Call back", "Text her", "Ignore"), done.message.actions)
    }

    @Test
    fun `a provider that ignores stream true still yields a message`() = runTest {
        // Some OpenAI-compatible gateways answer a stream request with one whole
        // completion body. Losing the reply entirely would look like a hang.
        val whole = JSONObject().put("choices", JSONArray().put(
            JSONObject().put("message", JSONObject()
                .put("role", "assistant").put("content", "Nothing urgent."))
        )).toString()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(whole))

        val done = client().run(spec(), history).toList()
            .filterIsInstance<AgentEvent.Done>().single()
        assertEquals("Nothing urgent.", done.message.content)
    }

    // ---- what actually gets sent -------------------------------------------

    @Test
    fun `only settled user and assistant turns are sent, and a small history goes whole`() = runTest {
        // A conversation well inside the budget is still re-sent in full — history budgeting changed
        // what happens at the ceiling, not the shape of an ordinary request.
        server.enqueueSse(delta("ok"), finish("stop"))
        val longer = listOf(
            ChatMessage(role = ChatMessage.Role.USER, content = "first"),
            ChatMessage(role = ChatMessage.Role.ASSISTANT, content = "reply"),
            ChatMessage(role = ChatMessage.Role.SYSTEM, content = "[Auto] dismissed something"),
            ChatMessage(role = ChatMessage.Role.ASSISTANT, content = "half typed", pending = true),
            ChatMessage(role = ChatMessage.Role.ASSISTANT, content = "boom", isError = true),
            ChatMessage(role = ChatMessage.Role.USER, content = "second")
        )

        client().run(spec(), longer).toList()

        val sent = messagesOf(server.takeRequest())
        assertEquals(listOf("system", "user", "assistant", "user"), sent.map { it.getString("role") })
        assertEquals(listOf("first", "reply", "second"), sent.drop(1).map { it.getString("content") })
        assertTrue("background decisions ride in the system prompt, not as turns",
            sent[0].getString("content").contains("[Auto] dismissed something"))
    }

    // ---- history budgeting: the request stops growing forever -----------------------------

    @Test
    fun `an oversized history is trimmed, keeping the opening request and the newest turn`() = runTest {
        server.enqueueSse(delta("ok"), finish("stop"))
        val bulky = { tag: String -> ChatMessage(role = ChatMessage.Role.ASSISTANT, content = tag + "x".repeat(2000)) }
        val longer = listOf(
            ChatMessage(role = ChatMessage.Role.USER, content = "book me a flight to Pittsburgh"),
            bulky("A"), bulky("B"), bulky("C"), bulky("D"),
            ChatMessage(role = ChatMessage.Role.USER, content = "did that work?")
        )

        client(budgetTokens = 400).run(spec(), longer).toList()

        val sent = messagesOf(server.takeRequest())
        val contents = sent.map { it.getString("content") }
        assertTrue("the opening request is pinned", contents.any { it == "book me a flight to Pittsburgh" })
        assertEquals("the question being asked is last", "did that work?", contents.last())
        assertTrue("the bulky middle went", sent.size < longer.size + 1)
        // Without this the model reads two distant turns as consecutive and invents the
        // transition — a silent failure, since the answer still looks fine. It rides in
        // the system prompt, not as a system turn mid-array: same call this client
        // already makes for background decisions, and not every compatible gateway
        // handles a non-leading system message the way OpenAI does.
        assertEquals("system", sent.first().getString("role"))
        assertTrue("the hole is declared in the system prompt: ${contents.first()}",
            contents.first().contains("omitted to stay within the context budget"))
        assertTrue("and nowhere else",
            contents.drop(1).none { it.contains("context budget") })
    }

    @Test
    fun `a history inside the budget is not trimmed and carries no gap notice`() = runTest {
        server.enqueueSse(delta("ok"), finish("stop"))
        val history = (1..20).map { ChatMessage(role = ChatMessage.Role.USER, content = "turn $it") }

        client(budgetTokens = 5_000).run(spec(), history).toList()

        val contents = messagesOf(server.takeRequest()).map { it.getString("content") }
        assertEquals("every turn plus the system prompt", 21, contents.size)
        assertTrue("nothing to declare", contents.none { it.contains("omitted") })
    }

    @Test
    fun `the system prompt and tool catalog are charged against the same budget`() = runTest {
        // The system prompt carries the live notification context, so it is not a small
        // constant. Reserving for it is what stops a busy notification shade from
        // silently pushing the request past the ceiling.
        val history = (1..100).map { ChatMessage(role = ChatMessage.Role.ASSISTANT, content = "turn $it") } +
            ChatMessage(role = ChatMessage.Role.USER, content = "latest")

        server.enqueueSse(delta("ok"), finish("stop"))
        OpenAiAiClient(
            apiKey = "test-key",
            endpoint = server.url("/v1/chat/completions").toString(),
            contextProvider = { "notifications:\n" + "n".repeat(4000) },
            budgetTokens = 1_200
        ).run(spec(), history).toList()
        val withFatContext = messagesOf(server.takeRequest()).size

        server.enqueueSse(delta("ok"), finish("stop"))
        client(budgetTokens = 1_200).run(spec(), history).toList()
        val withoutIt = messagesOf(server.takeRequest()).size

        assertTrue("a fat system prompt costs history turns ($withFatContext vs $withoutIt)",
            withFatContext < withoutIt)
    }

    // ---- usage reporting: what the turn actually cost ------------------------------------

    @Test
    fun `usage is requested and reported`() = runTest {
        server.enqueueSse(delta("Nothing urgent."), finish("stop"), usageChunk(1234, 56))

        val events = client().run(spec(), history).toList()

        val body = bodyOf(server.takeRequest())
        assertTrue("the provider has to be asked for usage",
            body.getJSONObject("stream_options").getBoolean("include_usage"))

        val usage = events.filterIsInstance<AgentEvent.Usage>().single()
        assertEquals(1234, usage.promptTokens)
        assertEquals(56, usage.completionTokens)
    }

    @Test
    fun `usage is reported per request so a tool loop can be summed`() = runTest {
        // The bill for a turn is every round-trip it made, not just the last one — a
        // tool loop's earlier requests are usually the expensive ones.
        server.enqueueSse(
            toolFrag(id = "call_1", name = "read_notifications", args = "{}"),
            finish("tool_calls"),
            usageChunk(1000, 20)
        )
        server.enqueueSse(delta("Mom called."), finish("stop"), usageChunk(1500, 40))

        val usage = client().run(spec(RecordingTool()), history).toList()
            .filterIsInstance<AgentEvent.Usage>()

        assertEquals("one per request, not one per turn", 2, usage.size)
        assertEquals(2500, usage.sumOf { it.promptTokens })
        assertEquals(60, usage.sumOf { it.completionTokens })
    }

    @Test
    fun `a provider that rejects stream_options gets the request again without it`() = runTest {
        // stream_options is an OpenAI extension; some compatible gateways 400 on it.
        // Losing all chat over a reporting nicety would be a bad trade, so the first
        // rejection turns it off for good.
        server.enqueue(MockResponse().setResponseCode(400).setBody("unknown field: stream_options"))
        server.enqueueSse(delta("Back online."), finish("stop"))

        val client = client()
        val done = client.run(spec(), history).toList()
            .filterIsInstance<AgentEvent.Done>().single()

        assertEquals("Back online.", done.message.content)
        assertEquals(2, server.requestCount)
        assertTrue("the first attempt asked for usage", bodyOf(server.takeRequest()).has("stream_options"))
        assertFalse("the retry dropped it", bodyOf(server.takeRequest()).has("stream_options"))

        // Sticky: re-probing on every turn would pay a wasted round-trip each time.
        server.enqueueSse(delta("Still fine."), finish("stop"))
        client.run(spec(), history).toList()
        assertFalse("and it stays off", bodyOf(server.takeRequest()).has("stream_options"))
    }

    @Test
    fun `a 400 that was not about stream_options surfaces at once and leaves usage on`() = runTest {
        // request controls (iii): only a rejection naming the field turns the option off. An unrelated
        // 400 is the user's error to see, with no second attempt — and it must not switch
        // usage reporting off for the rest of the process, for an unrelated error.
        server.enqueue(MockResponse().setResponseCode(400).setBody("model not found"))

        val client = client()
        val error = client.run(spec(), history).toList()
            .filterIsInstance<AgentEvent.Error>().single().error

        assertEquals(AgentError.Kind.BAD_REQUEST, error.kind)
        assertTrue("the real error propagates: ${error.message}",
            error.message.contains("model not found"))
        assertEquals("no fallback attempt", 1, server.requestCount)
        assertTrue(bodyOf(server.takeRequest()).has("stream_options"))

        server.enqueueSse(delta("Fixed."), finish("stop"))
        client.run(spec(), history).toList()
        assertTrue("usage is still asked for on the next turn", bodyOf(server.takeRequest()).has("stream_options"))
    }

    @Test
    fun `a rejection that names include_usage late in a long body still falls back`() = runTest {
        // Past the 200 characters the error message keeps.
        val body = "{\"error\":{\"message\":\"" + "x".repeat(400) + " unsupported parameter: include_usage\"}}"
        server.enqueue(MockResponse().setResponseCode(422).setBody(body))
        server.enqueueSse(delta("ok"), finish("stop"))

        client().run(spec(), history).toList()
        assertEquals(2, server.requestCount)
        assertTrue(bodyOf(server.takeRequest()).has("stream_options"))
        assertFalse(bodyOf(server.takeRequest()).has("stream_options"))
    }

    // ---- the turn scope tools run under -----------------------------

    @Test
    fun `selected calendar details survive trimming without becoming user evidence`() = runTest {
        val tool = TurnProbingTool()
        val source = ChatMessage(ChatMessage.Role.ASSISTANT,
            "Meet at Jim's office at 9pm tonight.", actions = listOf("Add to calendar"))
        val selected = SelectedAction.context(source, "Add to calendar")!!
        val messages = listOf(source,
            ChatMessage(ChatMessage.Role.ASSISTANT, "Unrelated later suggestion. ".repeat(500)),
            ChatMessage(ChatMessage.Role.USER, "Add to calendar", selectedActionContext = selected))
        server.enqueueSse(toolFrag(id = "call_1", name = "probe_turn", args = "{}"), finish("tool_calls"))
        server.enqueueSse(delta("prepared"), finish("stop"))

        client(budgetTokens = 100).run(spec(tool), messages).toList()

        val sent = messagesOf(server.takeRequest())
        assertTrue(sent.first().getString("content").contains("Jim's office at 9pm tonight"))
        assertTrue(sent.first().getString("content").contains("Derive a concise event title"))
        assertFalse(sent.any { it.optString("content").contains("Unrelated later suggestion") })
        assertEquals(listOf("Add to calendar"), sent.filter { it.getString("role") == "user" }
            .map { it.getString("content") })
        val evidence = requireNotNull(tool.seen).evidence
        assertTrue(evidence.attestsVerbatim("Add to calendar"))
        assertFalse(evidence.attestsVerbatim("Meet at Jim's office at 9pm tonight."))
        assertTrue(messagesOf(server.takeRequest()).first().getString("content").contains(selected))
    }

    @Test
    fun `a later typed request does not inherit a previous chip selection`() = runTest {
        val source = ChatMessage(ChatMessage.Role.ASSISTANT, "Jim at 9pm", actions = listOf("Add to calendar"))
        val messages = listOf(source,
            ChatMessage(ChatMessage.Role.USER, "Add to calendar",
                selectedActionContext = SelectedAction.context(source, "Add to calendar")),
            ChatMessage(ChatMessage.Role.USER, "What time is it?"))
        server.enqueueSse(delta("no selected action"), finish("stop"))
        client().run(spec(), messages).toList()
        assertFalse(messagesOf(server.takeRequest()).first().getString("content").contains("Selected suggestion JSON"))
    }

    /**
     * The one link [MemoryStoreTest] cannot check, because it installs the [AgentTurn]
     * itself. If the loop stopped installing it, every memory write would fail closed
     * and silently — safe, but the feature would be entirely gone with nothing failing.
     */
    @Test
    fun `a tool runs inside a turn carrying the users own words`() = runTest {
        val tool = TurnProbingTool()
        server.enqueueSse(toolFrag(id = "call_1", name = "probe_turn", args = "{}"), finish("tool_calls"))
        server.enqueueSse(delta("done"), finish("stop"))

        client().run(spec(tool), history).toList()

        val turn = requireNotNull(tool.seen) { "no AgentTurn was in scope inside the tool" }
        // The evidence is the current user turn, and it answers only the question
        // MemoryPolicy asks — there is no accessor for the raw text.
        assertTrue("evidence must attest what the user typed",
            turn.evidence.attestsVerbatim("what's urgent?"))
        assertFalse("and nothing else",
            turn.evidence.attestsVerbatim("the backup code is 998877"))
    }

    /**
     * The evidence is the NEWEST user turn, not any of them. A secret from three days ago
     * is not consent to store one now, and this is the assertion that pins that: the
     * history below carries an older user turn whose content must no longer attest.
     */
    @Test
    fun `the turn evidence is the newest user message only`() = runTest {
        val tool = TurnProbingTool()
        val longer = listOf(
            ChatMessage(role = ChatMessage.Role.USER, content = "my code is 445566"),
            ChatMessage(role = ChatMessage.Role.ASSISTANT, content = "noted"),
            ChatMessage(role = ChatMessage.Role.USER, content = "what's urgent?"),
        )
        server.enqueueSse(toolFrag(id = "call_1", name = "probe_turn", args = "{}"), finish("tool_calls"))
        server.enqueueSse(delta("done"), finish("stop"))

        client().run(spec(tool), longer).toList()

        val turn = requireNotNull(tool.seen)
        assertTrue(turn.evidence.attestsVerbatim("what's urgent?"))
        assertFalse("an earlier turn must not still attest",
            turn.evidence.attestsVerbatim("my code is 445566"))
    }
}
