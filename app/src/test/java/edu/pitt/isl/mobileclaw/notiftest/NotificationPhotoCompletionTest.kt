package edu.pitt.isl.mobileclaw.notiftest

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Exercises both completion branches through the real SSE loop, using only loopback fixtures. */
class NotificationPhotoCompletionTest {
    private fun response(delta: JSONObject, finishReason: String): MockResponse {
        val payload = JSONObject().put("choices", JSONArray().put(
            JSONObject().put("delta", delta).put("finish_reason", finishReason)))
        return MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: $payload\n\ndata: [DONE]\n\n")
    }

    private fun call(name: String, args: JSONObject = JSONObject()): MockResponse = response(
        JSONObject().put("tool_calls", JSONArray().put(JSONObject()
            .put("index", 0).put("id", "call_$name").put("type", "function")
            .put("function", JSONObject().put("name", name).put("arguments", args.toString())))),
        "tool_calls",
    )

    private fun answer(text: String): MockResponse = response(JSONObject().put("content", text), "stop")

    private class Fixture(result: String) {
        val draft = NotificationReplyDraft(setOf("search_files"))
        // No files, Android APIs or selection operations: this is only a per-turn candidate buffer.
        val photo = NotificationPhotoSuggestion(PhotoTurn("jvm-photo-completion", selectedPhoto = null), draft)
        private val reader = object : Tool {
            override val name = "search_files"
            override val description = "Read a fixture source"
            override val parameters = JSONObject("""{"type":"object","properties":{"query":{"type":"string"}}}""")
            override suspend fun invoke(args: JSONObject) = result
        }
        private val spec = FollowUp.spec(
            listOf(photo.tracking(reader), requireNotNull(photo.textReplyTool()), photo), "test-model",
        ).copy(completionReminder = photo::completionReminder, completionToolChoice = photo::nextRequiredTool)

        suspend fun run(server: MockWebServer): List<AgentEvent> = OpenAiAiClient(
            apiKey = "test-key", endpoint = server.url("/chat/completions").toString(), readTimeoutSeconds = 5,
        ).run(spec, listOf(ChatMessage(ChatMessage.Role.USER, FollowUp.SEED))).toList()
    }

    private fun MockWebServer.request(): JSONObject = JSONObject(
        requireNotNull(takeRequest(2, TimeUnit.SECONDS)) { "The client did not issue the expected request" }.body.readUtf8())

    @Test fun pdfSearchStillRequiresARealTextDraftAfterAPrematureStop() = runBlocking {
        MockWebServer().use { server ->
            val fixture = Fixture("Receipt PDF: the meeting starts at 10:30.")
            server.enqueue(call("search_files", JSONObject().put("query", "receipt")))
            server.enqueue(answer("I can prepare a reply if you ask."))
            server.enqueue(call("draft_notification_reply", JSONObject().put("message", "The meeting starts at 10:30.")))
            server.enqueue(answer("The reply is ready for review."))

            val events = fixture.run(server)
            assertTrue(events.none { it is AgentEvent.Error })
            assertTrue(events.filterIsInstance<AgentEvent.Done>().single().isComplete)
            assertEquals("The meeting starts at 10:30.", fixture.draft.message)
            assertFalse(fixture.photo.requested)
            assertEquals(listOf("search_files", "draft_notification_reply"), events.filterIsInstance<AgentEvent.ToolCall>().map { it.name })
            assertEquals(4, server.requestCount)
            server.request()
            server.request()
            val continuation = server.request()
            val system = continuation.getJSONArray("messages").getJSONObject(0).getString("content")
            assertTrue(system.contains("Completion requirement:"))
            assertTrue(system.contains("draft_notification_reply"))
            // The photo branch remains available, but the completion requirement cannot disappear.
            assertFalse(continuation.has("tool_choice"))
        }
    }

    @Test fun noMatchCanResolveThroughThePhotoBranchAfterAPrematureStop() = runBlocking {
        MockWebServer().use { server ->
            val fixture = Fixture("No files match.")
            server.enqueue(call("search_files", JSONObject().put("query", "trail")))
            server.enqueue(answer("No matching photos were found."))
            server.enqueue(call("suggest_photo_share"))
            server.enqueue(answer("No photo candidates are available to review."))

            val events = fixture.run(server)
            assertTrue(events.none { it is AgentEvent.Error })
            assertTrue(events.filterIsInstance<AgentEvent.Done>().single().isComplete)
            assertNull(fixture.draft.message)
            assertTrue(fixture.photo.resolved)
            assertEquals(listOf("search_files", "suggest_photo_share"), events.filterIsInstance<AgentEvent.ToolCall>().map { it.name })
            assertEquals(4, server.requestCount)
            server.request()
            server.request()
            val continuation = server.request()
            assertFalse("A zero-result photo search must not force an irrelevant text draft", continuation.has("tool_choice"))
            assertTrue(continuation.getJSONArray("messages").getJSONObject(0).getString("content").contains("suggest_photo_share"))
        }
    }

    @Test fun refusingToResolveEitherBranchRemainsIncompleteAtTheLoopBound() = runBlocking {
        MockWebServer().use { server ->
            val fixture = Fixture("Receipt PDF: the meeting starts at 10:30.")
            server.enqueue(call("search_files", JSONObject().put("query", "receipt")))
            repeat(6) { server.enqueue(answer("I can prepare a reply if you ask.")) }

            val events = fixture.run(server)
            assertTrue(events.none { it is AgentEvent.Error })
            assertFalse(events.filterIsInstance<AgentEvent.Done>().single().isComplete)
            assertNull(fixture.draft.message)
            assertFalse(fixture.photo.resolved)
            assertEquals(6, server.requestCount)
        }
    }
}
