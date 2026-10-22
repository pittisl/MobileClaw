package edu.pitt.isl.mobileclaw.notiftest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Network-free tests for the SSE reassembly ([SseTurn]) and the streamed-markdown
 * + [ACTIONS_SENTINEL] helpers ([StreamText]) — the fiddly logic that can't be
 * exercised on a device from here.
 */
class SseParsingTest {

    private fun SseTurn.feed(vararg payloads: String) = payloads.forEach { onPayload(it) }

    @Test
    fun `content fragments reassemble in order`() {
        val turn = SseTurn()
        turn.feed(
            """{"choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}""",
            """{"choices":[{"index":0,"delta":{"content":"Hel"}}]}""",
            """{"choices":[{"index":0,"delta":{"content":"lo"}}]}""",
            """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""
        )
        assertEquals("Hello", turn.content.toString())
        assertEquals("stop", turn.finishReason)
        assertFalse(turn.hasToolCalls())
    }

    @Test
    fun `onPayload returns only the new fragment`() {
        val turn = SseTurn()
        assertEquals("Hel", turn.onPayload("""{"choices":[{"delta":{"content":"Hel"}}]}"""))
        assertEquals("", turn.onPayload("""{"choices":[{"delta":{},"finish_reason":"stop"}]}"""))
    }

    @Test
    fun `usage is read from a chunk that carries nothing else`() {
        // The whole point of where [SseTurn.readUsage] sits. With
        // `stream_options.include_usage` the totals arrive in a trailing chunk that has
        // no content and no choice to iterate — providers disagree on whether `choices`
        // is an empty array or absent entirely, and *both* shapes short-circuit the
        // delta path. Read usage after either of those returns and it is silently lost.
        val empty = SseTurn()
        empty.feed(
            """{"choices":[{"delta":{"content":"hi"}}]}""",
            """{"choices":[],"usage":{"prompt_tokens":1200,"completion_tokens":340,"total_tokens":1540}}"""
        )
        assertEquals(1200, empty.promptTokens)
        assertEquals(340, empty.completionTokens)

        val absent = SseTurn()
        absent.feed("""{"usage":{"prompt_tokens":7,"completion_tokens":3}}""")
        assertEquals(7, absent.promptTokens)
        assertEquals(3, absent.completionTokens)
    }

    @Test
    fun `usage totals replace rather than accumulate`() {
        // Providers report a running total for the request, so summing successive
        // chunks would multiply the bill — and it would look plausible while doing it.
        val turn = SseTurn()
        turn.feed(
            """{"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":10}}""",
            """{"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":25}}"""
        )
        assertEquals(100, turn.promptTokens)
        assertEquals(25, turn.completionTokens)
    }

    @Test
    fun `a turn with no usage reported stays at zero`() {
        // Not every OpenAI-compatible gateway reports usage; zero is what the UI keys
        // its footer off, so it must not drift to a made-up number.
        val turn = SseTurn()
        turn.feed("""{"choices":[{"delta":{"content":"hi"}}]}""", """{"choices":[{"delta":{},"finish_reason":"stop"}]}""")
        assertEquals(0, turn.promptTokens)
        assertEquals(0, turn.completionTokens)
    }

    @Test
    fun `streamed tool call reassembles name and split arguments`() {
        val turn = SseTurn()
        turn.feed(
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_abc","type":"function","function":{"name":"send_sms","arguments":""}}]}}]}""",
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"to\":"}}]}}]}""",
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"+1\"}"}}]}}]}""",
            """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""
        )
        assertTrue(turn.hasToolCalls())
        assertEquals("tool_calls", turn.finishReason)
        val calls = turn.toolCalls()
        assertEquals(1, calls.size)
        val (id, name, args) = calls[0]
        assertEquals("call_abc", id)
        assertEquals("send_sms", name)
        assertEquals("""{"to":"+1"}""", args)
    }

    @Test
    fun `two parallel tool calls stay separate by index`() {
        val turn = SseTurn()
        turn.feed(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"a","function":{"name":"read_notifications","arguments":"{}"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":1,"id":"b","function":{"name":"get_current_time","arguments":"{}"}}]}}]}"""
        )
        val calls = turn.toolCalls()
        assertEquals(2, calls.size)
        assertEquals("read_notifications", calls[0].second)
        assertEquals("get_current_time", calls[1].second)
    }

    /**
     * Parallel calls may share index 0 while carrying distinct ids. They must remain
     * separate calls, with each argument stream associated with its own id; see
     * `SseTurn.slotFor`.
     */
    @Test
    fun `parallel tool calls that share index 0 but carry distinct ids stay separate`() {
        val turn = SseTurn()
        turn.feed(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"a","function":{"name":"read_calendar","arguments":"{\"range_end\":\"t\"}"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"b","function":{"name":"usage_context","arguments":"{\"window_minutes\":15}"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c","function":{"name":"recall","arguments":"{\"query\":"}}]}}]}""",
            // An id-less continuation at index 0 belongs to the call most recently opened
            // there, not to the first one.
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"Mom\"}"}}]}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"""
        )
        val calls = turn.toolCalls()
        assertEquals(3, calls.size)
        assertEquals(listOf("a", "b", "c"), calls.map { it.first })
        assertEquals(listOf("read_calendar", "usage_context", "recall"), calls.map { it.second })
        assertEquals("""{"range_end":"t"}""", calls[0].third)
        assertEquals("""{"window_minutes":15}""", calls[1].third)
        assertEquals("""{"query":"Mom"}""", calls[2].third)
        assertEquals(3, turn.assistantTurnJson().getJSONArray("tool_calls").length())
    }

    @Test
    fun `assistantTurnJson carries content and tool_calls`() {
        val turn = SseTurn()
        turn.feed(
            """{"choices":[{"delta":{"content":"one sec"}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"get_current_time","arguments":"{}"}}]}}]}"""
        )
        val json = turn.assistantTurnJson()
        assertEquals("assistant", json.getString("role"))
        assertEquals("one sec", json.getString("content"))
        val tc = json.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("call_1", tc.getString("id"))
        assertEquals("get_current_time", tc.getJSONObject("function").getString("name"))
    }

    @Test
    fun `fallback parses a whole non-streamed completion`() {
        val turn = SseTurn()
        val text = turn.fallbackFromWholeBody(
            """{"choices":[{"message":{"role":"assistant","content":"Hi there"}}]}"""
        )
        assertEquals("Hi there", text)
        assertEquals("Hi there", turn.content.toString())
    }

    @Test
    fun `fallback parses non-streamed tool calls keyed by position`() {
        val turn = SseTurn()
        turn.fallbackFromWholeBody(
            """{"choices":[{"message":{"tool_calls":[{"id":"call_1","function":{"name":"read_notifications","arguments":"{}"}}]}}]}"""
        )
        assertTrue(turn.hasToolCalls())
        assertEquals("read_notifications", turn.toolCalls()[0].second)
    }

    @Test
    fun `malformed payload is ignored`() {
        val turn = SseTurn()
        assertEquals("", turn.onPayload("not json"))
        assertEquals("", turn.onPayload("""{"choices":[]}"""))
        assertEquals("", turn.content.toString())
    }

    // ---- StreamText ----

    @Test
    fun `split returns text and no actions when no sentinel`() {
        val (text, actions) = StreamText.split("Hello **world**")
        assertEquals("Hello **world**", text)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `split separates trailing action labels`() {
        val (text, actions) = StreamText.split("Mom called 3 times.\n$ACTIONS_SENTINEL Call back | Text her | Ignore")
        assertEquals("Mom called 3 times.", text)
        assertEquals(listOf("Call back", "Text her", "Ignore"), actions)
    }

    @Test
    fun `split caps at four actions`() {
        val (_, actions) = StreamText.split("hi $ACTIONS_SENTINEL a | b | c | d | e")
        assertEquals(listOf("a", "b", "c", "d"), actions)
    }

    @Test
    fun `visible hides a fully-arrived sentinel and its tail`() {
        assertEquals("Hi", StreamText.visible("Hi\n$ACTIONS_SENTINEL A | B"))
    }

    @Test
    fun `visible hides a partially-arrived sentinel`() {
        assertEquals("Hello ", StreamText.visible("Hello @@AC"))
        assertEquals("Hello world", StreamText.visible("Hello world"))
    }
}
