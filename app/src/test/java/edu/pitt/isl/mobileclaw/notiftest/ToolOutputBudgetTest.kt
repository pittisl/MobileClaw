package edu.pitt.isl.mobileclaw.notiftest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic that bounds one turn's tool loop.
 *
 * Worth having on its own, above the end-to-end coverage in `AgentLoopTest`, for the
 * same reason `HistoryBudgetTest` is: a budget that keeps the wrong results still
 * produces a plausible answer — one composed from output the model was never shown.
 * Nothing downstream can notice that, so these assertions are the only thing that will.
 */
class ToolOutputBudgetTest {

    /** Text of a known token cost, in the estimator's terms (chars/4). */
    private fun sized(tokens: Int) = "x".repeat(tokens * 4)

    private fun budget(tokens: Int) = ToolOutputBudget(budgetTokens = tokens)

    @Test
    fun `results that fit are passed through untouched`() {
        val b = budget(1_000)
        val first = sized(100)
        val second = sized(100)

        assertEquals(listOf(first), b.admit("read_notifications", first))
        assertEquals(listOf(first, second), b.admit("read_calendar", second))
    }

    @Test
    fun `the newest result is always present, truncated rather than dropped`() {
        // Rule 1. A single privileged dump can exceed the whole budget by itself; the
        // model asked for it and is composing an answer from it, so "omit it" is not an
        // available answer — it comes back shorter, and says so.
        val b = budget(100)
        val huge = sized(5_000)

        val rendered = b.admit("file_system", huge).single()

        assertTrue("it was cut down", rendered.length < huge.length)
        assertTrue("and it starts with the real output", rendered.startsWith("xxxx"))
        assertTrue("the truncation is announced: $rendered", rendered.contains("truncated"))
        assertTrue("with both figures, so the model knows how much it is missing",
            rendered.contains("${huge.length} characters"))
    }

    @Test
    fun `a truncated newest result stays within the budget it was truncated to`() {
        // The notice [fit] appends is itself tokens. If it weren't reserved for, the
        // "capped" result would come in over the cap — quietly, and only for the
        // biggest outputs, which is exactly where it matters.
        val b = budget(500)

        val rendered = b.admit("file_system", sized(5_000)).single()

        assertTrue("~${HistoryBudget.estimateTokens(rendered)} tokens against a 500 budget",
            HistoryBudget.estimateTokens(rendered) <= 500)
    }

    @Test
    fun `an older result is evicted to keep the newest one whole`() {
        // Rule 2, the case this class exists for: two 16k-token shell dumps in one turn.
        // The recent one is what the model is reasoning about, so the older one becomes
        // a notice rather than both being halved into uselessness.
        val b = budget(1_000)
        val old = sized(800)
        val new = sized(800)

        b.admit("file_system", old)
        val rendered = b.admit("memory_monitor", new)

        assertEquals(new, rendered[1])
        assertFalse("the older one is gone", rendered[0] == old)
        assertTrue("replaced by a notice naming the tool: ${rendered[0]}",
            rendered[0].contains("file_system"))
        assertTrue("and its size, so a re-read can be narrowed", rendered[0].contains("3200 characters"))
    }

    @Test
    fun `eviction latches, so what the model sees is a recent run and a summarised prefix`() {
        // No hunting backwards for a smaller result that happens to fit: that would
        // interleave real output with notices, and the model would have to guess which
        // step each belonged to.
        val b = budget(1_000)
        val tiny = sized(10)

        b.admit("tool_a", tiny)          // would still fit in what's left …
        b.admit("tool_b", sized(980))    // … but this is what runs the budget out
        val rendered = b.admit("tool_c", sized(50))

        assertTrue("the newest is whole", rendered[2].length == 200)
        assertFalse("tool_b evicted", rendered[1].startsWith("xxxx"))
        assertFalse("and tool_a with it, though it would have fit", rendered[0].startsWith("xxxx"))
        assertTrue(rendered[0].contains("tool_a"))
    }

    @Test
    fun `eviction is monotone - what was dropped once never reappears`() {
        // The rewrite is recomputed from the original texts on every call, so nothing
        // structurally stops a result from coming back once a later, smaller one leaves
        // room. It must not: the model already saw the notice saying that output was
        // gone, and re-admitting it re-bills output that was written off.
        val b = budget(1_000)
        b.admit("file_system", sized(900))
        val afterSecond = b.admit("memory_monitor", sized(900))
        assertFalse("evicted when the second arrived", afterSecond[0].startsWith("xxxx"))

        // A tiny newest leaves far more room than the 900-token one it replaces at the
        // head of the scan — the case where a naive recomputation would un-evict.
        val rendered = b.admit("get_current_time", sized(10))

        assertFalse("and still evicted three results in", rendered[0].startsWith("xxxx"))
        assertTrue(rendered[0].contains("file_system"))
    }

    @Test
    fun `every admitted result gets exactly one rendering, in order`() {
        // The caller writes these back positionally onto the tool messages already in
        // the request array. A short or reordered list would attach one call's output to
        // another call's id.
        val b = budget(200)
        b.admit("a", sized(150))
        b.admit("b", sized(150))
        val rendered = b.admit("c", sized(150))

        assertEquals(3, rendered.size)
        assertTrue(rendered.all { it.isNotEmpty() })
    }

    @Test
    fun `the total carried stays bounded however many results arrive`() {
        // The whole point: 5 iterations of 64 KB output must not add up to a 5x bill.
        val b = budget(2_000)
        var rendered = emptyList<String>()
        repeat(12) { rendered = b.admit("file_system", sized(4_000)) }

        val total = rendered.sumOf { HistoryBudget.estimateTokens(it) }
        assertTrue("~$total tokens carried against a 2000 budget", total <= 2_000 + 12 * 40)
    }

    @Test
    fun `an empty result is carried as itself`() {
        // A tool that legitimately returns nothing must not be reported to the model as
        // an omission — that would read as "there was output and you can't see it".
        val rendered = budget(1_000).admit("dismiss_notification", "").single()

        assertEquals("", rendered)
    }
}
