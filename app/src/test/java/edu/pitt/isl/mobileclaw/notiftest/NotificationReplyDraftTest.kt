package edu.pitt.isl.mobileclaw.notiftest

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Pure draft-buffer and real client-loop checks; no Android delivery surface is used.
 * Canned SSE cases prove transport, tool sequencing and provenance, not model reasoning
 * or live document-search reliability. The listener's binding to a live notification
 * and the approval UI require instrumented tests separately.
 */
class NotificationReplyDraftTest {
    private fun args(message: String) = JSONObject().put("message", message)

    @Test fun `a new buffer has no proposal`() {
        val draft = NotificationReplyDraft()
        assertNull(draft.message)
        assertNull(draft.proposal("Sender") { error("No draft can send") })
    }

    @Test fun `the schema accepts reply or hold text and cannot choose a recipient or key`() {
        val schema = NotificationReplyDraft().parameters
        assertEquals(setOf("message", "hold_reason"), schema.getJSONObject("properties").keys().asSequence().toSet())
        assertFalse(schema.getBoolean("additionalProperties"))
    }

    @Test fun `saving a trimmed draft promises review rather than sending`() = runTest {
        val draft = NotificationReplyDraft()
        val result = draft.invoke(args("  Arriving at 12:30; please confirm early check-in.  "))
        assertEquals("Arriving at 12:30; please confirm early check-in.", draft.message)
        assertTrue(result.contains("user review"))
        assertTrue(result.contains("Nothing has been sent"))
    }

    @Test fun `missing message clears a previous draft`() = runTest {
        val draft = NotificationReplyDraft()
        draft.invoke(args("Previous draft"))
        assertTrue(draft.invoke(JSONObject()).startsWith("Draft not saved"))
        assertNull(draft.message)
        assertNull(draft.proposal("Sender") { error("Invalid revision must not send") })
    }

    @Test fun `non-string revisions clear a previous draft without coercion`() = runTest {
        val draft = NotificationReplyDraft()
        for (value in listOf(42, true, JSONObject.NULL, JSONObject(), JSONArray())) {
            draft.invoke(args("Previous draft"))
            assertTrue(draft.invoke(JSONObject().put("message", value)).startsWith("Draft not saved"))
            assertNull("Invalid value $value left a draft", draft.message)
        }
    }

    @Test fun `blank revisions clear a previous draft`() = runTest {
        val draft = NotificationReplyDraft()
        for (value in listOf("", "  ", "\n\t")) {
            draft.invoke(args("Previous draft"))
            assertTrue(draft.invoke(args(value)).startsWith("Draft not saved"))
            assertNull(draft.message)
        }
    }

    @Test fun `extra recipient key or approval fields clear a previous draft`() = runTest {
        val draft = NotificationReplyDraft()
        for (field in listOf("recipient", "notificationKey", "approved", "send")) {
            draft.invoke(args("Previous draft"))
            val result = draft.invoke(args("Revised draft").put(field, "injected"))
            assertTrue(result.startsWith("Draft not saved"))
            assertNull("Unexpected $field must not preserve either draft", draft.message)
        }
    }

    @Test fun `the four-thousand character boundary is enforced after trimming`() = runTest {
        val draft = NotificationReplyDraft()
        val maximum = "x".repeat(4_000)
        draft.invoke(args("  $maximum\n"))
        assertEquals(maximum, draft.message)
        assertTrue(draft.invoke(args(maximum + "x")).startsWith("Draft not saved"))
        assertNull(draft.message)
    }

    @Test fun `a proposal forbids full automation and sending waits for execute`() = runTest {
        val sent = mutableListOf<String>()
        val draft = NotificationReplyDraft()
        draft.invoke(args("Please confirm our 12:30 arrival."))
        val proposal = requireNotNull(draft.proposal("Messages: Cedar House") {
            sent += it
            "Sent exact text"
        })
        assertFalse(proposal.allowFullAutomation)
        assertEquals("Reply to Messages: Cedar House: \"Please confirm our 12:30 arrival.\"", proposal.description)
        assertTrue("Neither invoking the tool nor constructing a proposal sends", sent.isEmpty())
        assertEquals("Sent exact text", proposal.execute())
        assertEquals(listOf("Please confirm our 12:30 arrival."), sent)
    }

    @Test fun `approval snapshots the text even when the buffer is revised or invalidated`() = runTest {
        val sent = mutableListOf<String>()
        val draft = NotificationReplyDraft()
        draft.invoke(args("Text the user reviewed"))
        val proposal = requireNotNull(draft.proposal("Original sender") { sent += it; "sent" })
        draft.invoke(args("Later replacement"))
        draft.invoke(JSONObject())
        assertNull(draft.message)
        assertTrue(sent.isEmpty())
        proposal.execute()
        assertEquals(listOf("Text the user reviewed"), sent)
        assertTrue(proposal.description.contains("Text the user reviewed"))
        assertFalse(proposal.description.contains("Later replacement"))
    }

    @Test fun `a new buffer cannot inherit another notifications draft`() = runTest {
        val first = NotificationReplyDraft()
        first.invoke(args("First notification only"))
        val second = NotificationReplyDraft()
        assertNull(second.message)
        assertNull(second.proposal("Different sender") { error("No cross-notification draft") })
        assertEquals("First notification only", first.message)
    }

    @Test fun `only reply triage with an inline text action is eligible`() {
        val withReply = notif("Question")
        val withoutReply = withReply.copy(actions = listOf(NotifAction(0, "Open", acceptsText = false)))
        for (action in ActionDecision.Action.entries) {
            assertEquals(action == ActionDecision.Action.REPLY,
                NotificationReplyDraft.eligible(withReply, decision(action)))
            assertFalse(NotificationReplyDraft.eligible(withoutReply, decision(action)))
            assertFalse(NotificationReplyDraft.eligible(withReply.copy(actions = emptyList()), decision(action)))
        }
    }

    @Test fun `a valid hold resolves completion while withdrawing an earlier draft`() = runTest {
        val draft = NotificationReplyDraft()
        assertNotNull(draft.completionReminder())
        draft.invoke(args("A prior draft"))
        assertNull(draft.completionReminder())
        val result = draft.invoke(JSONObject().put("hold_reason", "  The document cannot be found  "))
        assertTrue(result.startsWith("Reply withheld: The document cannot be found."))
        assertNull(draft.completionReminder())
        assertNull(draft.message)
        assertNull(draft.proposal("Original sender") { error("A hold cannot send") })
    }

    @Test fun `invalid hold reasons reopen completion and clear the earlier draft`() = runTest {
        val draft = NotificationReplyDraft()
        for (reason in listOf("", " \n\t", "x".repeat(4_001), 42, true, JSONObject.NULL, JSONObject(), JSONArray())) {
            draft.invoke(args("Earlier draft"))
            assertNull(draft.completionReminder())
            val result = draft.invoke(JSONObject().put("hold_reason", reason))
            assertTrue(result.startsWith("Draft not saved"))
            assertNotNull("Invalid hold must not resolve the turn", draft.completionReminder())
            assertNull(draft.message)
            assertNull(draft.proposal("Original sender") { error("Invalid hold cannot send an earlier draft") })
        }
    }

    @Test fun `message and hold together are rejected and neither decision survives`() = runTest {
        val draft = NotificationReplyDraft()
        draft.invoke(args("Earlier draft"))
        val result = draft.invoke(args("New draft").put("hold_reason", "Missing source"))
        assertTrue(result.startsWith("Draft not saved"))
        assertNull(draft.message)
        assertNotNull(draft.completionReminder())
        assertNull(draft.proposal("Original sender") { error("Conflicting choices cannot send") })
        draft.invoke(JSONObject().put("hold_reason", "Missing source"))
        assertNull(draft.completionReminder())
        draft.onInvocationStarted()
        assertNotNull("Even an earlier hold is invalidated before a revision is parsed", draft.completionReminder())
    }

    private fun notif(body: String) = CapturedNotif(
        key = "original-notification-key", packageName = "com.example.messages", appName = "Messages",
        postTimeMs = 1_800_000_000_000L, title = "Demo sender", text = body, subText = "",
        isMessaging = true, isOngoing = false, posted = true, category = "msg",
        actions = listOf(NotifAction(0, "Reply", acceptsText = true)),
    )

    private fun decision(action: ActionDecision.Action = ActionDecision.Action.REPLY) = ActionDecision(
        notificationKey = "original-notification-key", action = action,
        priority = ActionDecision.Priority.MEDIUM, reasoning = "Needs context", replyText = "Unverified triage draft",
        confidence = 0.9f, snoozeDurationMinutes = null,
    )

    private class Reader(
        override val name: String, private val result: String, private val beforeRead: (() -> Unit)? = null,
    ) : Tool {
        override val description = "Test reader"
        override val parameters = JSONObject("""{"type":"object","properties":{}}""")
        val calls = mutableListOf<JSONObject>()
        var turn: AgentTurn? = null
        override suspend fun invoke(args: JSONObject): String {
            beforeRead?.invoke()
            calls += args
            turn = coroutineContext[AgentTurn]
            return result
        }
    }

    private fun sse(vararg chunks: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(chunks.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n")

    private fun call(id: String, name: String, args: JSONObject) = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("delta", JSONObject().put("tool_calls", JSONArray().put(
            JSONObject().put("index", 0).put("id", id).put("function",
                JSONObject().put("name", name).put("arguments", args.toString()))
        )))
    )).toString()

    private fun finish(reason: String) = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("delta", JSONObject()).put("finish_reason", reason)
    )).toString()

    private fun answer(text: String) = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("delta", JSONObject().put("content", text))
    )).toString()

    private fun JSONObject.messages(): List<JSONObject> = getJSONArray("messages").let { array ->
        (0 until array.length()).map { array.getJSONObject(it) }
    }

    private fun MockWebServer.requestBody(): JSONObject = JSONObject(
        requireNotNull(takeRequest(2, TimeUnit.SECONDS)) { "Expected client request" }.body.readUtf8())

    private suspend fun runFollowUp(
        server: MockWebServer, body: String, readers: List<Tool>, draft: NotificationReplyDraft,
        completionGate: Boolean = true,
        namedToolChoice: Boolean = false,
    ): List<AgentEvent> = OpenAiAiClient(
        apiKey = "test-key", endpoint = server.url("/v1/chat/completions").toString(),
        contextProvider = { FollowUp.contextBlock(notif(body), decision(), nowMs = 1_800_000_000_000L) },
    ).run(
        FollowUp.spec(FollowUp.readOnlyTools(readers).map(draft::tracking) + draft, "test-model").copy(
            completionReminder = if (completionGate) draft::completionReminder else null,
            completionToolChoice = if (namedToolChoice) draft::nextRequiredTool else null),
        listOf(ChatMessage(role = ChatMessage.Role.USER, content = FollowUp.SEED)),
    ).toList()

    @Test fun `real client carries calendar and PDF results into an automatic draft and done`() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            val incoming = "CEDAR_SOURCE_ONLY: What is the booking code in our PDF and is our arrival before check-in?"
            val calendarText = "Calendar: Oct 25 hotel arrival 12:30."
            val pdfText = "hotel-confirmation.pdf OCR: booking LKVQ7X; standard check-in 15:00."
            val reply = "Booking LKVQ7X: we plan to arrive at 12:30, before the 15:00 check-in. Please confirm early check-in."
            val calendar = Reader("read_calendar", calendarText)
            val search = Reader("search_files", pdfText)
            val memoryWriter = Reader("remember", "must never run")
            val forget = Reader("forget", "must never run")
            val sender = Reader("reply_notification", "must never run")
            val draft = NotificationReplyDraft()
            val sent = mutableListOf<String>()
            server.enqueue(sse(call("calendar", calendar.name, JSONObject().put("date", "2026-10-25")), finish("tool_calls")))
            server.enqueue(sse(call("pdf", search.name, JSONObject().put("query", "Cedar House booking")), finish("tool_calls")))
            server.enqueue(sse(call("draft", draft.name, args(reply)), finish("tool_calls")))
            server.enqueue(sse(answer("**Hotel arrival:** The PDF and calendar support this draft; review before sending."), finish("stop")))

            val events = runFollowUp(server, incoming, listOf(calendar, search, memoryWriter, forget, sender), draft)
            assertEquals(listOf("read_calendar", "search_files", "draft_notification_reply"),
                events.filterIsInstance<AgentEvent.ToolCall>().map { it.name })
            assertEquals(1, calendar.calls.size)
            assertEquals("2026-10-25", calendar.calls.single().getString("date"))
            assertEquals("Cedar House booking", search.calls.single().getString("query"))
            assertEquals(reply, draft.message)
            assertTrue(events.filterIsInstance<AgentEvent.Error>().isEmpty())
            assertEquals(1, events.filterIsInstance<AgentEvent.Done>().size)
            assertTrue(events.filterIsInstance<AgentEvent.Done>().single().isComplete)
            assertEquals(4, server.requestCount)

            val requests = (0 until 4).map { server.requestBody() }
            for (request in requests) {
                val messages = request.messages()
                assertEquals(FollowUp.SEED, messages.single { it.getString("role") == "user" }.getString("content"))
                assertFalse(messages.single { it.getString("role") == "user" }.toString().contains("CEDAR_SOURCE_ONLY"))
                assertTrue(messages.first().getString("content").contains(incoming))
                val tools = request.getJSONArray("tools")
                val names = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") }
                assertEquals(setOf(calendar.name, search.name, draft.name), names.toSet())
            }
            fun toolResults(request: JSONObject) = request.messages().filter { it.getString("role") == "tool" }
                .associate { it.getString("tool_call_id") to it.getString("content") }
            assertEquals(mapOf("calendar" to calendarText), toolResults(requests[1]))
            assertEquals(mapOf("calendar" to calendarText, "pdf" to pdfText), toolResults(requests[2]))
            assertEquals(calendarText, toolResults(requests[3])["calendar"])
            assertEquals(pdfText, toolResults(requests[3])["pdf"])
            assertTrue(requireNotNull(toolResults(requests[3])["draft"]).contains("Nothing has been sent"))
            for (reader in listOf(calendar, search)) {
                val turn = requireNotNull(reader.turn)
                assertTrue(turn.evidence.attestsVerbatim(FollowUp.SEED))
                assertFalse(turn.evidence.attestsVerbatim(incoming))
                assertEquals(0, turn.attemptedWrites)
            }
            assertTrue(memoryWriter.calls.isEmpty())
            assertTrue(forget.calls.isEmpty())
            assertTrue(sender.calls.isEmpty())
            val proposal = requireNotNull(draft.proposal("Original sender") { sent += it; "sent" })
            assertFalse(proposal.allowFullAutomation)
            assertTrue(sent.isEmpty())
            proposal.execute()
            assertEquals(listOf(reply), sent)
        } finally {
            server.shutdown()
        }
    }

    @Test fun `canned invoice search exercises the same draft route without hotel vocabulary`() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            val search = Reader("search_files", "invoice.pdf OCR: invoice INV-2048; payment due November 8.")
            val draft = NotificationReplyDraft()
            val reply = "The invoice is INV-2048 and payment is due November 8."
            server.enqueue(sse(call("invoice", search.name, JSONObject().put("query", "workshop invoice")), finish("tool_calls")))
            server.enqueue(sse(call("draft", draft.name, args(reply)), finish("tool_calls")))
            server.enqueue(sse(answer("**Invoice:** I found the reference and due date; review the prepared reply."), finish("stop")))
            val events = runFollowUp(server, "What are the number and due date on the workshop invoice?", listOf(search), draft)
            assertEquals(reply, draft.message)
            assertEquals(listOf(search.name, draft.name), events.filterIsInstance<AgentEvent.ToolCall>().map { it.name })
            assertEquals(3, server.requestCount)
            server.requestBody()
            val second = server.requestBody().messages().single { it.getString("role") == "tool" }
            assertTrue(second.getString("content").contains("INV-2048"))
            assertNotNull(draft.proposal("Invoice sender") { error("Constructing a draft must not send") })
        } finally {
            server.shutdown()
        }
    }

    @Test fun `canned missing document response can end without manufacturing a reply proposal`() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            val search = Reader("search_files", "No matching documents found.")
            val draft = NotificationReplyDraft()
            server.enqueue(sse(call("missing", search.name, JSONObject().put("query", "workshop invoice")), finish("tool_calls")))
            server.enqueue(sse(call("hold", draft.name, JSONObject().put("hold_reason", "Invoice not found")), finish("tool_calls")))
            server.enqueue(sse(answer("**Invoice:** I could not find the document, so its number and due date remain unknown."), finish("stop")))
            val events = runFollowUp(server, "What are the number and due date on the workshop invoice?", listOf(search), draft)
            assertEquals(1, search.calls.size)
            assertEquals(listOf(search.name, draft.name), events.filterIsInstance<AgentEvent.ToolCall>().map { it.name })
            assertNull(draft.message)
            assertNull(draft.proposal("Invoice sender") { error("A missing document did not create a draft") })
            assertEquals(3, server.requestCount)
            server.requestBody()
            assertEquals("No matching documents found.", server.requestBody().messages()
                .single { it.getString("role") == "tool" }.getString("content"))
            assertTrue(events.filterIsInstance<AgentEvent.Done>().single().message.content.contains("remain unknown"))
            assertTrue(events.filterIsInstance<AgentEvent.Done>().single().isComplete)
            assertNull(draft.completionReminder())
        } finally {
            server.shutdown()
        }
    }

    @Test fun `a saved draft does not make a capped tool loop complete`() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            val draft = NotificationReplyDraft()
            val search = Reader("search_files", "Another search result")
            server.enqueue(sse(call("draft", draft.name, args("Draft awaiting further verification")), finish("tool_calls")))
            repeat(5) { index ->
                server.enqueue(sse(call("search-$index", search.name, JSONObject().put("query", "document $index")), finish("tool_calls")))
            }
            val events = runFollowUp(server, "Check the document before replying.", listOf(search), draft)
            assertEquals("Draft awaiting further verification", draft.message)
            assertEquals(6, server.requestCount)
            assertEquals("The request beyond the cap must not execute", 4, search.calls.size)
            assertFalse(events.filterIsInstance<AgentEvent.Done>().single().isComplete)
            assertTrue(events.filterIsInstance<AgentEvent.Error>().isEmpty())
        } finally {
            server.shutdown()
        }
    }

    @Test fun `a length-truncated response after saving a draft is incomplete`() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            val draft = NotificationReplyDraft()
            server.enqueue(sse(call("draft", draft.name, args("Draft awaiting final checks")), finish("tool_calls")))
            server.enqueue(sse(answer("The document says"), finish("length")))
            val events = runFollowUp(server, "Check the document before replying.", emptyList(), draft)
            assertEquals("Draft awaiting final checks", draft.message)
            assertEquals(2, server.requestCount)
            val done = events.filterIsInstance<AgentEvent.Done>().single()
            assertEquals("The document says", done.message.content)
            assertFalse(done.isComplete)
        } finally {
            server.shutdown()
        }
    }

    @Test fun `EOF without finish reason after saving a draft is incomplete`() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            val draft = NotificationReplyDraft()
            server.enqueue(sse(call("draft", draft.name, args("Draft awaiting final checks")), finish("tool_calls")))
            // Deliberately neither finish_reason nor [DONE]: a clean socket EOF is not
            // evidence that an autonomous drafting turn finished its verification.
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: ${answer("The document says")}\n\n"))
            val events = runFollowUp(server, "Check the document before replying.", emptyList(), draft)
            assertEquals("Draft awaiting final checks", draft.message)
            assertEquals(2, server.requestCount)
            val done = events.filterIsInstance<AgentEvent.Done>().single()
            assertEquals("The document says", done.message.content)
            assertFalse(done.isComplete)
        } finally {
            server.shutdown()
        }
    }

    @Test fun `malformed JSON revision invalidates an earlier draft before parsing fails`() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            val draft = NotificationReplyDraft()
            server.enqueue(sse(call("first-draft", draft.name, args("Earlier draft must be withdrawn")), finish("tool_calls")))
            val malformed = JSONObject(call("bad-revision", draft.name, JSONObject())).apply {
                getJSONArray("choices").getJSONObject(0).getJSONObject("delta")
                    .getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function")
                    .put("arguments", "{\"message\":")
            }.toString()
            server.enqueue(sse(malformed, finish("tool_calls")))
            server.enqueue(sse(answer("I could not prepare a valid revised draft."), finish("stop")))

            // Isolate the parsing hook here. The separately tested completion gate
            // would require another draft-or-hold decision after the invalid revision.
            val events = runFollowUp(server, "Check the document before replying.", emptyList(), draft,
                completionGate = false)
            assertEquals(3, server.requestCount)
            assertEquals(listOf(draft.name, draft.name), events.filterIsInstance<AgentEvent.ToolCall>().map { it.name })
            val results = events.filterIsInstance<AgentEvent.ToolResult>()
            assertEquals(2, results.size)
            assertTrue("The first invocation really saved a draft", results[0].result.startsWith("Draft saved"))
            assertTrue("The second invocation failed before invoke could clear it",
                results[1].result.startsWith("Error running ${draft.name}:"))
            assertTrue(events.filterIsInstance<AgentEvent.Done>().single().isComplete)
            // Without invokeTool's pre-parse hook, the old successful draft survives
            // the JSON exception and this completed turn would expose it for approval.
            assertNull(draft.message)
            assertNull(draft.proposal("Original sender") { error("Malformed revision must withdraw the draft") })
        } finally {
            server.shutdown()
        }
    }

    @Test fun `an unfinished stop is continued by system reminder without a new user turn`() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            val draft = NotificationReplyDraft()
            val premature = "UNFINISHED_PROSE: I could draft this for you.\n@@ACTIONS@@ Draft a reply | Ignore"
            val completed = "**Reply ready:** Review the prepared text before sending."
            server.enqueue(sse(answer(premature), finish("stop")))
            server.enqueue(sse(call("draft", draft.name, args("Please confirm the appointment time.")), finish("tool_calls")))
            server.enqueue(sse(answer(completed), finish("stop")))

            val events = runFollowUp(server, "APPOINTMENT_SOURCE_ONLY: Can you confirm this appointment?", emptyList(), draft)
            assertEquals(3, server.requestCount)
            assertEquals("Please confirm the appointment time.", draft.message)
            assertNull(draft.completionReminder())
            assertEquals(listOf(draft.name), events.filterIsInstance<AgentEvent.ToolCall>().map { it.name })
            val done = events.filterIsInstance<AgentEvent.Done>().single()
            assertTrue(done.isComplete)
            assertEquals(completed, done.message.content)
            assertTrue(done.message.actions.isEmpty())
            assertEquals("Unfinished prose and draft chips must never flash in the UI", completed,
                events.filterIsInstance<AgentEvent.TokenDelta>().joinToString("") { it.text })

            val requests = (0 until 3).map { server.requestBody() }
            for (request in requests) {
                assertFalse("A reminder-only spec preserves automatic tool choice", request.has("tool_choice"))
                val messages = request.messages()
                assertEquals("Only the leading message is SYSTEM", 1, messages.count { it.getString("role") == "system" })
                assertEquals("system", messages.first().getString("role"))
                assertEquals(FollowUp.SEED, messages.single { it.getString("role") == "user" }.getString("content"))
                assertFalse(messages.single { it.getString("role") == "user" }.toString().contains("APPOINTMENT_SOURCE_ONLY"))
            }
            val originalSystem = requests[0].messages().first().getString("content")
            assertFalse(originalSystem.contains("Completion requirement:"))
            val continued = requests[1].messages()
            assertTrue(continued.first().getString("content").startsWith(originalSystem + "\n\nCompletion requirement:\n"))
            assertTrue(continued.first().getString("content").contains("hold_reason"))
            assertEquals(premature, continued.single { it.getString("role") == "assistant" }.getString("content"))
            assertTrue(requests[2].messages().single { it.getString("role") == "tool" }
                .getString("content").startsWith("Draft saved"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun `repeated unfinished stops share the six request cap and expose no unfinished prose`() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            val draft = NotificationReplyDraft()
            repeat(6) { index ->
                server.enqueue(sse(answer("UNFINISHED_PROSE_$index\n@@ACTIONS@@ Draft a reply | Ignore"), finish("stop")))
            }
            val events = runFollowUp(server, "Check the document before replying.", emptyList(), draft)
            assertEquals(6, server.requestCount)
            assertTrue(events.filterIsInstance<AgentEvent.TokenDelta>().isEmpty())
            assertTrue(events.filterIsInstance<AgentEvent.ToolCall>().isEmpty())
            val done = events.filterIsInstance<AgentEvent.Done>().single()
            assertFalse(done.isComplete)
            assertFalse(done.message.content.contains("UNFINISHED_PROSE"))
            assertTrue(done.message.actions.isEmpty())
            assertNull(draft.message)
            assertNotNull(draft.completionReminder())
            assertNull(draft.proposal("Original sender") { error("A capped incomplete turn cannot send") })
            repeat(6) { index ->
                val messages = server.requestBody().messages()
                assertEquals(1, messages.count { it.getString("role") == "system" })
                assertEquals(FollowUp.SEED, messages.single { it.getString("role") == "user" }.getString("content"))
                assertEquals(index, messages.count { it.getString("role") == "assistant" })
                assertEquals("The requirement replaces its prior copy instead of growing on each retry",
                    if (index == 0) 0 else 1,
                    Regex("Completion requirement:").findAll(messages.first().getString("content")).count())
            }
        } finally {
            server.shutdown()
        }
    }

    @Test fun `ordinary JSON completions preserve stop versus length after a saved draft`() = runTest {
        for (finishReason in listOf("stop", "length")) {
            val server = MockWebServer().apply { start() }
            try {
                val draft = NotificationReplyDraft()
                server.enqueue(sse(call("draft", draft.name, args("Please confirm the appointment time.")), finish("tool_calls")))
                val response = JSONObject().put("choices", JSONArray().put(JSONObject()
                    .put("message", JSONObject().put("role", "assistant").put("content", "Review this prepared reply."))
                    .put("finish_reason", finishReason)))
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(response.toString()))
                val events = runFollowUp(server, "Confirm the appointment time.", emptyList(), draft)
                assertEquals(2, server.requestCount)
                assertEquals("Please confirm the appointment time.", draft.message)
                assertTrue(events.filterIsInstance<AgentEvent.Error>().isEmpty())
                val done = events.filterIsInstance<AgentEvent.Done>().single()
                assertEquals("Review this prepared reply.", done.message.content)
                assertEquals("Whole-body completion with finish_reason=$finishReason", finishReason == "stop", done.isComplete)
            } finally {
                server.shutdown()
            }
        }
    }

    @Test fun `required sources cannot be fulfilled by registration or claimed reads`() = runTest {
        val draft = NotificationReplyDraft(setOf("search_files", "read_calendar"))
        val search = Reader("search_files", "Document text")
        val calendar = Reader("read_calendar", "Calendar text")
        val trackedSearch = draft.tracking(search)
        draft.tracking(calendar)
        assertTrue(requireNotNull(draft.completionReminder()).contains("search_files"))
        assertTrue(requireNotNull(draft.completionReminder()).contains("read_calendar"))

        // Neither creating a wrapper nor a model's prose claiming a read is a return
        // from the tracked tool. The unwrapped call is outside this turn's bookkeeping.
        search.invokeWithReceipt(JSONObject().put("query", "document"))
        val claimed = draft.invoke(args("I checked the PDF and calendar; both are confirmed."))
        assertTrue(claimed.startsWith("Draft not saved"))
        assertTrue(claimed.contains("search_files"))
        assertTrue(claimed.contains("read_calendar"))
        assertNull(draft.message)
        assertNull(draft.proposal("Sender") { error("A claimed read must not unlock sending") })

        val invalid = draft.invoke(args("Ready").put("completedReaders", JSONArray().put("search_files").put("read_calendar")))
        assertTrue(invalid.startsWith("Draft not saved"))
        trackedSearch.invokeWithReceipt(JSONObject().put("query", "document"))
        val stillMissing = draft.invoke(args("Only the document was checked"))
        assertTrue(stillMissing.contains("read_calendar"))
        assertFalse(stillMissing.contains("search_files"))
        assertNull(draft.message)
        assertTrue(calendar.calls.isEmpty())
    }

    @Test fun `permission and no-match returns complete a lookup but do not establish facts or create a draft`() = runTest {
        for (result in listOf("capability_unavailable: file permission denied", "No matching documents found.")) {
            val draft = NotificationReplyDraft(setOf("search_files"))
            val search = Reader("search_files", result)
            val invocation = draft.tracking(search).invokeWithReceipt(JSONObject().put("query", "invoice"))
            assertEquals(result, invocation.text)
            assertEquals(1, search.calls.size)
            assertFalse(requireNotNull(draft.completionReminder()).contains("Required sources still unread"))
            assertNull("A returned lookup is not a prepared reply", draft.message)
            assertNull(draft.proposal("Sender") { error("A failed lookup alone cannot send") })
            assertTrue(draft.invoke(JSONObject().put("hold_reason", "The invoice facts cannot be verified: $result"))
                .startsWith("Reply withheld"))
            assertNull(draft.completionReminder())
            assertNull(draft.proposal("Sender") { error("A hold cannot send") })
        }
    }

    @Test fun `a throwing reader does not fulfill its source requirement and hold remains available`() = runTest {
        val draft = NotificationReplyDraft(setOf("search_files"))
        val throwing = object : Tool {
            override val name = "search_files"
            override val description = "Throws before returning"
            override val parameters = JSONObject()
            override suspend fun invoke(args: JSONObject): String = throw IllegalStateException("reader failed")
        }
        var failure: IllegalStateException? = null
        try { draft.tracking(throwing).invokeWithReceipt(JSONObject().put("query", "invoice")) }
        catch (error: IllegalStateException) { failure = error }
        assertEquals("reader failed", requireNotNull(failure).message)
        assertTrue(requireNotNull(draft.completionReminder()).contains("search_files"))
        assertTrue(draft.invoke(args("An unsupported answer")).startsWith("Draft not saved"))
        assertNull(draft.message)
        draft.invoke(JSONObject().put("hold_reason", "Document lookup failed; its facts remain unknown"))
        assertNull(draft.completionReminder())
        assertNull(draft.proposal("Sender") { error("No document evidence and no draft") })
    }

    @Test fun `real client rejects a premature document-only draft then reads calendar and completes`() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            val incoming = "Cedar House PDF booking code? Is our Oct 25 arrival before check-in?"
            val draft = NotificationReplyDraft(NotificationContextRequirements.readersFor(incoming))
            val search = Reader("search_files", "hotel-confirmation.pdf: LKVQ7X; check-in 15:00.")
            var checkedDraftDuringCalendar = false
            var draftBeforeCalendar: String? = "not checked"
            val calendar = Reader("read_calendar", "Oct 25: planned hotel arrival 12:30.") {
                checkedDraftDuringCalendar = true
                draftBeforeCalendar = draft.message
            }
            val reply = "Booking LKVQ7X; our planned arrival is 12:30, before the 15:00 check-in. Please confirm early check-in."
            server.enqueue(sse(call("pdf", search.name, JSONObject().put("query", "Cedar House")), finish("tool_calls")))
            server.enqueue(sse(call("premature", draft.name, args("Booking LKVQ7X; check-in is 15:00.")), finish("tool_calls")))
            server.enqueue(sse(call("calendar", calendar.name, JSONObject().put("date", "2026-10-25")), finish("tool_calls")))
            server.enqueue(sse(call("complete-draft", draft.name, args(reply)), finish("tool_calls")))
            server.enqueue(sse(answer("**Hotel reply:** Calendar and PDF have been checked; review the prepared text."), finish("stop")))

            val events = runFollowUp(server, incoming, listOf(search, calendar), draft)
            assertEquals(5, server.requestCount)
            assertEquals(listOf(search.name, draft.name, calendar.name, draft.name),
                events.filterIsInstance<AgentEvent.ToolCall>().map { it.name })
            assertEquals(1, search.calls.size)
            assertEquals(1, calendar.calls.size)
            assertTrue(checkedDraftDuringCalendar)
            assertNull("Observe synchronously on the invocation coroutine, without a collector race", draftBeforeCalendar)
            val results = events.filterIsInstance<AgentEvent.ToolResult>().associate { it.id to it.result }
            assertTrue(requireNotNull(results["premature"]).startsWith("Draft not saved"))
            assertTrue(requireNotNull(results["premature"]).contains("read_calendar"))
            assertTrue(requireNotNull(results["complete-draft"]).startsWith("Draft saved"))
            assertEquals(reply, draft.message)
            assertNull(draft.completionReminder())
            assertTrue(events.filterIsInstance<AgentEvent.Done>().single().isComplete)
            val requests = (0 until 5).map { server.requestBody() }
            val refusal = requests[2].messages().single {
                it.getString("role") == "tool" && it.getString("tool_call_id") == "premature"
            }.getString("content")
            assertTrue("The next model request receives the deterministic refusal", refusal.contains("read_calendar"))
            val finalResults = requests.last().messages().filter { it.getString("role") == "tool" }
                .associate { it.getString("tool_call_id") to it.getString("content") }
            assertTrue(requireNotNull(finalResults["pdf"]).contains("LKVQ7X"))
            assertTrue(requireNotNull(finalResults["calendar"]).contains("12:30"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun `a search return with missing or blank query does not fulfill its source requirement`() = runTest {
        for (searchArgs in listOf(JSONObject(), JSONObject().put("query", ""), JSONObject().put("query", " \n\t "))) {
            val draft = NotificationReplyDraft(setOf("search_files"))
            val search = Reader("search_files", "No search performed: query is required.")
            val result = draft.tracking(search).invokeWithReceipt(searchArgs)
            assertEquals("No search performed: query is required.", result.text)
            assertEquals("The reader returned, but no meaningful lookup was requested", 1, search.calls.size)
            assertTrue(requireNotNull(draft.completionReminder()).contains("Required sources still unread: search_files"))
            assertTrue(draft.invoke(args("Unsupported reply after an empty query")).startsWith("Draft not saved"))
            assertNull(draft.message)
            assertNull(draft.proposal("Sender") { error("Empty search query must not unlock a proposal") })
        }
    }

    @Test fun `a real nonblank search after a rejected empty query can fulfill the requirement`() = runTest {
        val draft = NotificationReplyDraft(setOf("search_files"))
        val search = Reader("search_files", "invoice.pdf: reference INV-2048")
        val tracked = draft.tracking(search)
        tracked.invokeWithReceipt(JSONObject().put("query", " "))
        assertTrue(draft.invoke(args("Premature reply")).startsWith("Draft not saved"))
        tracked.invokeWithReceipt(JSONObject().put("query", "  workshop invoice  "))
        assertEquals(2, search.calls.size)
        assertEquals("  workshop invoice  ", search.calls.last().getString("query"))
        assertFalse(requireNotNull(draft.completionReminder()).contains("Required sources still unread"))
        assertTrue(draft.invoke(args("The invoice reference is INV-2048.")).startsWith("Draft saved"))
        assertEquals("The invoice reference is INV-2048.", draft.message)
        assertNull(draft.completionReminder())
    }

    private fun JSONObject.namedToolChoice(): String? {
        if (!has("tool_choice")) return null
        val choice = getJSONObject("tool_choice")
        assertEquals("function", choice.getString("type"))
        return choice.getJSONObject("function").getString("name")
    }

    @Test fun `premature stop enables named search then calendar then draft and returns to automatic choice`() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            val incoming = "Cedar House PDF booking code? Is our Oct 25 arrival before check-in?"
            val draft = NotificationReplyDraft(NotificationContextRequirements.readersFor(incoming))
            val search = Reader("search_files", "PDF: booking LKVQ7X; check-in 15:00.")
            val calendar = Reader("read_calendar", "Calendar: planned arrival 12:30.")
            val reply = "Booking LKVQ7X; our 12:30 arrival precedes the 15:00 check-in. Please confirm early check-in."
            val finalText = "**Reply ready:** Review this prepared reply before sending."
            server.enqueue(sse(answer("UNFINISHED_PROSE\n@@ACTIONS@@ Draft a reply | Ignore"), finish("stop")))
            server.enqueue(sse(call("pdf", search.name, JSONObject().put("query", "Cedar House PDF")), finish("tool_calls")))
            server.enqueue(sse(call("calendar", calendar.name, JSONObject().put("date", "2026-10-25")), finish("tool_calls")))
            server.enqueue(sse(call("draft", draft.name, args(reply)), finish("tool_calls")))
            server.enqueue(sse(answer(finalText), finish("stop")))

            val events = runFollowUp(server, incoming, listOf(search, calendar), draft, namedToolChoice = true)
            assertEquals(5, server.requestCount)
            val requests = (0 until 5).map { server.requestBody() }
            assertEquals(listOf(null, search.name, calendar.name, draft.name, null), requests.map { it.namedToolChoice() })
            for (request in requests) {
                val messages = request.messages()
                assertEquals(FollowUp.SEED, messages.single { it.getString("role") == "user" }.getString("content"))
                assertEquals(1, messages.count { it.getString("role") == "system" })
                val catalog = request.getJSONArray("tools")
                val names = (0 until catalog.length()).map { catalog.getJSONObject(it).getJSONObject("function").getString("name") }
                assertEquals(setOf(search.name, calendar.name, draft.name), names.toSet())
            }
            assertEquals("Cedar House PDF", search.calls.single().getString("query"))
            assertEquals("2026-10-25", calendar.calls.single().getString("date"))
            assertEquals(reply, draft.message)
            assertNull(draft.nextRequiredTool())
            assertTrue(events.filterIsInstance<AgentEvent.Done>().single().isComplete)
            assertEquals(finalText, events.filterIsInstance<AgentEvent.TokenDelta>().joinToString("") { it.text })
        } finally {
            server.shutdown()
        }
    }

    @Test fun `a hold after a named draft decision removes named choice without creating a proposal`() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            val draft = NotificationReplyDraft(setOf("search_files"))
            val search = Reader("search_files", "No matching documents found.")
            server.enqueue(sse(answer("I can look for the invoice."), finish("stop")))
            server.enqueue(sse(call("search", search.name, JSONObject().put("query", "workshop invoice")), finish("tool_calls")))
            server.enqueue(sse(call("hold", draft.name, JSONObject().put("hold_reason", "Invoice not found; cannot verify its number")), finish("tool_calls")))
            server.enqueue(sse(answer("**Invoice:** Its number remains unknown because the document was not found."), finish("stop")))

            val events = runFollowUp(server, "What number is on the invoice?", listOf(search), draft, namedToolChoice = true)
            assertEquals(4, server.requestCount)
            val requests = (0 until 4).map { server.requestBody() }
            assertEquals(listOf(null, search.name, draft.name, null), requests.map { it.namedToolChoice() })
            requests.forEach { request ->
                assertEquals(FollowUp.SEED, request.messages().single { it.getString("role") == "user" }.getString("content"))
            }
            assertNull(draft.message)
            assertNull(draft.nextRequiredTool())
            assertNull(draft.completionReminder())
            assertNull(draft.proposal("Sender") { error("Named tool choice does not force an unsupported reply") })
            assertTrue(events.filterIsInstance<AgentEvent.Done>().single().isComplete)
        } finally {
            server.shutdown()
        }
    }

    @Test fun `a provider ignoring named choice still stops at the shared six request cap`() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            val draft = NotificationReplyDraft(setOf("search_files"))
            val search = Reader("search_files", "Must never be called by an empty model response")
            repeat(6) { server.enqueue(sse(finish("stop"))) }
            val events = runFollowUp(server, "Check the PDF before replying.", listOf(search), draft, namedToolChoice = true)
            assertEquals(6, server.requestCount)
            val requests = (0 until 6).map { server.requestBody() }
            assertEquals(listOf(null) + List(5) { search.name }, requests.map { it.namedToolChoice() })
            assertTrue(search.calls.isEmpty())
            assertTrue(events.filterIsInstance<AgentEvent.ToolCall>().isEmpty())
            assertTrue(events.filterIsInstance<AgentEvent.TokenDelta>().isEmpty())
            assertFalse(events.filterIsInstance<AgentEvent.Done>().single().isComplete)
            assertNull(draft.message)
            assertEquals(search.name, draft.nextRequiredTool())
            assertNull(draft.proposal("Sender") { error("Ignored tool choice cannot manufacture a draft") })
            requests.forEach { request ->
                assertEquals(FollowUp.SEED, request.messages().single { it.getString("role") == "user" }.getString("content"))
            }
        } finally {
            server.shutdown()
        }
    }
}
