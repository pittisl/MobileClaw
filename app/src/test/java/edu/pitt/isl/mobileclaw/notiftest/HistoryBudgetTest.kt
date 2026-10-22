package edu.pitt.isl.mobileclaw.notiftest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The history-budget arithmetic: which turns survive a per-request token budget.
 *
 * Pure JVM, no device and no network — the same reasoning as [FsPolicyTest]. What
 * makes these worth writing is that the failure mode is *silent*: a window that drops
 * the wrong turn still produces a plausible-looking answer, just one that has quietly
 * forgotten what it was asked to do. Nothing downstream can notice that.
 */
class HistoryBudgetTest {

    private fun user(text: String) = ChatMessage(role = ChatMessage.Role.USER, content = text)
    private fun assistant(text: String) = ChatMessage(role = ChatMessage.Role.ASSISTANT, content = text)

    /** A turn costing roughly [tokens] tokens, at the estimator's 4 chars each. */
    private fun bulky(tag: String, tokens: Int) = assistant(tag + "x".repeat(tokens * 4))

    private fun contents(w: HistoryBudget.Window) = w.messages.map { it.content }

    // ---- what is eligible at all --------------------------------------------

    @Test
    fun `pending, error and system turns never enter the window`() {
        // Pending placeholders and error bubbles must stay out of model history. SYSTEM
        // turns are folded into the system prompt rather than sent as conversation turns.
        val w = HistoryBudget.select(
            listOf(
                user("real question"),
                assistant("half typed").copy(pending = true),
                assistant("boom").copy(isError = true),
                ChatMessage(role = ChatMessage.Role.SYSTEM, content = "[Auto] dismissed something"),
                assistant("real answer")
            )
        )
        assertEquals(listOf("real question", "real answer"), contents(w))
        assertEquals(0, w.dropped)
    }

    @Test
    fun `an empty history yields an empty window rather than throwing`() {
        val w = HistoryBudget.select(emptyList())
        assertTrue(w.messages.isEmpty())
        assertEquals(0, w.dropped)
        assertEquals(-1, w.gapAfterIndex)
    }

    // ---- the three rules ----------------------------------------------------

    @Test
    fun `a history that fits is sent whole, in order`() {
        val history = listOf(user("first"), assistant("reply"), user("second"))
        val w = HistoryBudget.select(history, budgetTokens = 10_000)
        assertEquals(listOf("first", "reply", "second"), contents(w))
        assertEquals(0, w.dropped)
        assertEquals("nothing to warn about", -1, w.gapAfterIndex)
    }

    @Test
    fun `the newest turn is sent even when it alone blows the budget`() {
        // Rule 1. Dropping the question being asked to stay under budget produces a
        // request that costs less and answers nothing — strictly worse than paying.
        val w = HistoryBudget.select(
            listOf(bulky("old", 100), user("a".repeat(4000))),
            budgetTokens = 50
        )
        assertEquals(1, w.messages.size)
        assertTrue(w.messages.single().content.startsWith("a"))
        assertTrue("it really is over budget", w.estimatedTokens > 50)
    }

    @Test
    fun `the first user turn is pinned when the middle has to be dropped`() {
        // Rule 2, and the reason this isn't a plain sliding window: in an agent session
        // the opening request is what every later turn is in service of.
        val history = listOf(
            user("find my flight confirmation and add it to my calendar"),
            bulky("tool output", 200),
            bulky("more tool output", 200),
            assistant("nearly there"),
            user("did that work?")
        )
        val w = HistoryBudget.select(history, budgetTokens = 120)

        assertEquals("the opening request survives",
            "find my flight confirmation and add it to my calendar", w.messages.first().content)
        assertEquals("so does the newest turn", "did that work?", w.messages.last().content)
        assertTrue("the bulky middle is what paid for it", w.dropped >= 2)
    }

    @Test
    fun `the window fills backwards from the newest`() {
        // Rule 3: recency is what carries the thread, so the fill runs newest-first and
        // the turns that fall out are the oldest non-pinned ones.
        val history = (1..10).map { assistant("turn $it") } + user("latest")
        val w = HistoryBudget.select(history, budgetTokens = 40)

        assertEquals("latest", w.messages.last().content)
        assertTrue("older turns went first: ${contents(w)}", "turn 1" !in contents(w))
        assertTrue("recent ones stayed", "turn 10" in contents(w))
    }

    @Test
    fun `the gap is reported where it actually is`() {
        // Without this the model reads the pinned first turn and the recent tail as
        // consecutive, and will happily invent the transition between them.
        val history = listOf(
            user("original request"),
            bulky("middle a", 300),
            bulky("middle b", 300),
            user("latest")
        )
        val w = HistoryBudget.select(history, budgetTokens = 100)

        assertEquals("the hole opens right after the pinned head", 0, w.gapAfterIndex)
        assertEquals(2, w.dropped)

        val notice = HistoryBudget.trimNotice(w)!!
        assertTrue(notice, notice.contains("2 earlier turn"))
        assertTrue("a mid-conversation hole says so: $notice",
            notice.contains("between the first turn and the recent ones"))
    }

    @Test
    fun `turns dropped off the front are described differently from a hole`() {
        // Without the head pin there is no hole, just a shorter conversation — and
        // telling the model something was cut from the middle when it wasn't would send
        // it looking for a gap that doesn't exist.
        val history = listOf(assistant("old"), assistant("older"), user("latest"))
        val w = HistoryBudget.select(history, budgetTokens = 8)

        assertEquals("no hole, just a truncated front", -1, w.gapAfterIndex)
        assertTrue(w.dropped > 0)
        assertTrue(HistoryBudget.trimNotice(w)!!.contains("at the start of the conversation"))
    }

    @Test
    fun `an untrimmed window has nothing to announce`() {
        // ~30 tokens of prompt on every single turn if this ever returns non-null when
        // nothing was dropped — and a model told to look for a gap that isn't there.
        val w = HistoryBudget.select(listOf(user("first"), assistant("reply")), budgetTokens = 10_000)
        assertEquals(null, HistoryBudget.trimNotice(w))
    }

    // ---- the budget is shared with the rest of the request -------------------

    @Test
    fun `reserved tokens shrink the history, not the other way round`() {
        // The system prompt carries the live notification context, so it is not a small
        // constant: 30 notifications on screen must cost history, not silently overrun.
        val history = (1..6).map { assistant("turn $it") } + user("latest")

        val roomy = HistoryBudget.select(history, budgetTokens = 200, reservedTokens = 0)
        val cramped = HistoryBudget.select(history, budgetTokens = 200, reservedTokens = 190)

        assertEquals("everything fits when nothing is reserved", 0, roomy.dropped)
        assertTrue("a fat system prompt costs turns", cramped.dropped > 0)
        assertEquals("but never the newest one", "latest", cramped.messages.last().content)
    }

    @Test
    fun `reserving more than the whole budget still sends the question`() {
        val w = HistoryBudget.select(
            listOf(user("first"), assistant("reply"), user("latest")),
            budgetTokens = 100,
            reservedTokens = 500
        )
        assertEquals(listOf("latest"), contents(w))
    }

    // ---- the estimator ------------------------------------------------------

    @Test
    fun `short turns are not free`() {
        // A history of hundreds of two-word turns has a real cost — role markers and
        // chat-template separators — and counting only characters would call it zero.
        val many = (1..100).map { user("ok") }
        val w = HistoryBudget.select(many, budgetTokens = 100)
        assertTrue("100 tiny turns don't all fit in 100 tokens", w.dropped > 0)
    }

    @Test
    fun `the estimator rounds up so no text counts as zero tokens`() {
        assertEquals(0, HistoryBudget.estimateTokens(""))
        assertEquals(1, HistoryBudget.estimateTokens("a"))
        assertEquals(1, HistoryBudget.estimateTokens("abcd"))
        assertEquals(2, HistoryBudget.estimateTokens("abcde"))
    }
}
