package edu.pitt.isl.mobileclaw.notiftest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trace's formatting, and — the reason this file exists — its redaction guarantee.
 *
 * A JVM test rather than an instrumented one for the same reason as `FsPolicyTest` and
 * `HistoryBudgetTest`: the formatters were split out of the emitters precisely so the
 * interesting half needs no device. `Log.i` is never reached from here.
 *
 * The property under test is not cosmetic. The trace prints to logcat, which anyone with
 * adb can read, and it is fed from paths that are **legitimately unredacted** — the user-attested
 * redaction exception stores what the user typed verbatim. If the trace trusted its inputs, the one
 * path that was allowed to carry a phone number would put it on a projector.
 */
class ReasoningTraceTest {

    // A string containing one of every class `ReasoningModule.redact` knows about, so a
    // pattern that stops being applied at this boundary fails here rather than in Austin.
    private val SENSITIVE = "code 483920, card 4111 1111 1111 1111, ssn 123-45-6789, " +
        "call 412-555-0100, mail a@b.com, password: hunter2, key ABCD-EFGH-IJKL"

    private fun assertScrubbed(rendered: String) {
        listOf(
            "483920", "4111 1111 1111 1111", "123-45-6789",
            "412-555-0100", "a@b.com", "hunter2", "ABCD-EFGH-IJKL"
        ).forEach {
            assertFalse("\"$it\" reached the trace unredacted:\n$rendered", rendered.contains(it))
        }
        assertTrue("nothing was redacted at all", rendered.contains("[REDACTED]"))
    }

    @Test
    fun `a user-typed turn is redacted even though it is stored verbatim`() {
        // The user-attested memory path. This is the one input the trace cannot assume is clean:
        // everywhere else scrubs on the way in, and USER content deliberately does not.
        val rendered = ReasoningTrace.formatChatTurnStart(
            sessionTitle = "General Chat Agent",
            systemPrompt = "You are a proactive assistant.",
            history = listOf(ChatMessage(role = ChatMessage.Role.USER, content = SENSITIVE)),
            toolCount = 15,
            droppedTurns = 0
        )
        assertScrubbed(rendered)
    }

    @Test
    fun `the system prompt is redacted, carrying the live notification context with it`() {
        val rendered = ReasoningTrace.formatChatTurnStart(
            sessionTitle = "General Chat Agent",
            systemPrompt = "You are an assistant.\n\nCaptured notifications:\n  $SENSITIVE",
            history = emptyList(),
            toolCount = 15,
            droppedTurns = 0
        )
        assertScrubbed(rendered)
    }

    @Test
    fun `tool results are redacted`() {
        // read_notifications and read_calendar both return device content verbatim from
        // the caller's point of view; the trace is the last place to catch it.
        assertScrubbed(ReasoningTrace.formatToolResult("read_notifications", SENSITIVE))
    }

    @Test
    fun `the model's own answer is redacted`() {
        // A model that quotes an OTP back is producing it as *output*, which no upstream
        // redaction covers — nothing scrubs completions on the way to the screen.
        assertScrubbed(ReasoningTrace.formatChatTurnEnd("stop", SENSITIVE, 100, 20))
    }

    @Test
    fun `a triage block is redacted in title, body and reasoning`() {
        val decision = ActionDecision(
            notificationKey = "k",
            action = ActionDecision.Action.REPLY,
            priority = ActionDecision.Priority.HIGH,
            reasoning = SENSITIVE,
            replyText = SENSITIVE,
            confidence = 0.94f,
            snoozeDurationMinutes = null
        )
        assertScrubbed(ReasoningTrace.formatTriage("WhatsApp", SENSITIVE, SENSITIVE, decision))
    }

    // ---- the parts a reader depends on ----------------------------------------------

    @Test
    fun `a triage block states the action, priority and confidence`() {
        // 0.876543f, not 0.94f. Kotlin prints 0.94f as exactly "0.94", so a test using it
        // would pass whether or not the formatting existed — it asserts nothing. This
        // value differs between `%.2f` and `toString()`, which is the difference the
        // formatting is there to make.
        val decision = ActionDecision(
            notificationKey = "k",
            action = ActionDecision.Action.ESCALATE,
            priority = ActionDecision.Priority.HIGH,
            reasoning = "Time-sensitive question.",
            replyText = "",
            confidence = 0.876543f,
            snoozeDurationMinutes = null
        )
        val rendered = ReasoningTrace.formatTriage("WhatsApp", "Priya", "2pm?", decision)
        assertTrue(rendered, rendered.contains("escalate"))
        assertTrue(rendered, rendered.contains("high"))
        // Two decimals. A long float tail in front of an audience reads as a bug in the
        // model rather than as a float.
        assertTrue(rendered, rendered.contains("confidence 0.88"))
        assertFalse(rendered, rendered.contains("0.876543"))
    }

    @Test
    fun `a snooze states its duration and other actions do not`() {
        fun render(action: ActionDecision.Action, minutes: Int?) = ReasoningTrace.formatTriage(
            "Todoist", "Slides", "due today",
            ActionDecision(
                notificationKey = "k", action = action,
                priority = ActionDecision.Priority.MEDIUM, reasoning = "",
                replyText = "", confidence = 0.8f, snoozeDurationMinutes = minutes
            )
        )
        assertTrue(render(ActionDecision.Action.SNOOZE, 90).contains("snooze 90m"))
        assertFalse(render(ActionDecision.Action.DISMISS, null).contains("snooze"))
    }

    @Test
    fun `a turn with no prose says so rather than printing an empty answer`() {
        // A capped tool loop can finish with tool calls and no assistant text. Do not
        // render an empty ANSWER block that would imply the model failed to respond.
        val rendered = ReasoningTrace.formatChatTurnEnd("capped", "", 0, 0)
        assertTrue(rendered, rendered.contains("tool calls only"))
    }

    @Test
    fun `token cost appears only when the provider reported it`() {
        assertTrue(ReasoningTrace.formatChatTurnEnd("stop", "hi", 1200, 84).contains("1200 in / 84 out"))
        assertFalse(ReasoningTrace.formatChatTurnEnd("stop", "hi", 0, 0).contains(" in / "))
    }

    @Test
    fun `every line of a chat turn names the turn it belongs to`() {
        // Triage, follow-ups and typed turns interleave in the log; the tag is the only
        // thing that says which turn a tool call or an answer was part of.
        assertTrue(ReasoningTrace.formatChatTurnStart("General", "sys", emptyList(), 3, 0, turn = 7).lines().first().contains("CHAT TURN #7 "))
        assertTrue(ReasoningTrace.formatToolCall(7, "search_files", "{}").startsWith("▸ TOOL CALL  #7  search_files"))
        assertTrue(ReasoningTrace.formatToolResult("search_files", "ok", turn = 7).startsWith("▸ TOOL RESULT  #7  search_files"))
        assertTrue(ReasoningTrace.formatChatTurnEnd("stop", "hi", 1, 1, turn = 7).startsWith("▸ ANSWER  #7  (finish=stop"))
    }
}
