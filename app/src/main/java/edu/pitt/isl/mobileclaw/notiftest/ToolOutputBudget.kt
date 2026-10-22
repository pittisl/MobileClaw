package edu.pitt.isl.mobileclaw.notiftest

/**
 * Bounds the tool output carried *inside a single turn's tool loop*.
 *
 * [HistoryBudget] bounds what crosses turns, and stops at the loop's door: it runs once,
 * before the first request, and the `role:"tool"` messages the loop appends after that
 * are charged against nothing. Each iteration re-sends everything accumulated so far, so
 * the unbounded cost within one turn grows quadratically, as across a session.
 *
 * The numbers are not hypothetical. A privileged command's stdout is capped at
 * [ShellCommandPolicy.MAX_OUTPUT_BYTES] (64 KB) and reaches the model raw — call it
 * ~16 000 tokens — and `MAX_TOOL_ITERATIONS` is 5. A turn that fills them is well past
 * the 30 000-token ceiling that bounds everything else, and it is the most expensive
 * kind of turn to begin with.
 *
 * Results cannot simply be dropped the way old conversation turns can: the model asked
 * for them, and the answer it is composing depends on them. So the two rules mirror
 * [HistoryBudget]'s, for the same reasons:
 *
 *  1. **The newest result is always present**, truncated to the budget if it alone
 *     exceeds it. It is what the model is reasoning about right now; a request that
 *     carries the question but not the answer to it is strictly worse than a big one.
 *  2. **Everything else fills backwards from the newest.** At the first result that no
 *     longer fits, that one and everything older become a one-line notice — no hunting
 *     for smaller ones further back, which would scatter the loop's record into holes.
 *
 * Every reduction says so out loud, like [ShellCommandPolicy.cap]: a model reads a
 * silently-partial listing as a complete one, and here it would go on to answer from it.
 *
 * Deliberately **not** solved here: results still die with the turn, so the model has no
 * memory of what it read a turn ago. Persisting them into `ChatStore` would put an
 * unbounded stream of tool output into every *later* prompt — the problem this class
 * exists to prevent, moved one level up. If that is ever worth fixing, what gets
 * persisted is a summary, charged against [HistoryBudget] like anything else.
 *
 * No Android imports, so the arithmetic is testable with no device
 * (`ToolOutputBudgetTest`) — the same reasoning as [HistoryBudget] and [FsPolicy].
 */
internal class ToolOutputBudget(private val budgetTokens: Int = DEFAULT_BUDGET_TOKENS) {

    /** Every result admitted so far, oldest first, as the tool actually returned it. */
    private val results = mutableListOf<Result>()

    private data class Result(val toolName: String, val text: String)

    /**
     * Records [result] as the output of [toolName] and returns what **all** admitted
     * results should now say, oldest first and positionally aligned with the order they
     * were admitted in.
     *
     * The caller rewrites the whole set rather than just appending, because admitting a
     * large result is what evicts the earlier ones — the `role:"tool"` message for an
     * older call stays in the array (dropping it would orphan its `tool_call_id`) and
     * has its content replaced. Rewriting all of them keeps that in one place and is
     * idempotent; there are at most a few dozen.
     */
    fun admit(toolName: String, result: String): List<String> {
        results += Result(toolName, result)

        val rendered = arrayOfNulls<String>(results.size)
        val newest = results.lastIndex

        // Rule 1: unconditional, budget or not — but truncated to it.
        rendered[newest] = fit(results[newest].text)
        var used = cost(rendered[newest]!!)

        // Rule 2: fill backwards, then summarise the rest. `evicting` latches instead of
        // re-testing each older result, so what the model sees is one contiguous run of
        // recent results and a summarised prefix, never an interleaving of the two.
        var evicting = false
        for (i in newest - 1 downTo 0) {
            val text = results[i].text
            if (!evicting && used + cost(text) <= budgetTokens) {
                rendered[i] = text
                used += cost(text)
            } else {
                evicting = true
                rendered[i] = omissionNotice(results[i])
                // Counted, though it can't evict anything further: a notice is ~25
                // tokens and honest accounting is cheaper than explaining why it isn't.
                used += cost(rendered[i]!!)
            }
        }

        @Suppress("UNCHECKED_CAST")
        return (rendered as Array<String>).toList()
    }

    /** Whole if it fits; otherwise a head-truncated prefix that says what it lost. */
    private fun fit(text: String): String {
        if (cost(text) <= budgetTokens) return text
        // The head is the part worth keeping: `ls`, `ps` and a file read all put their
        // most identifying output first, and a model that has the start of a listing can
        // ask for the rest by narrowing its next call.
        val keep = ((budgetTokens - TRUNCATION_NOTICE_TOKENS) * CHARS_PER_TOKEN)
            .coerceAtLeast(0)
        if (keep >= text.length) return text
        return text.take(keep) +
            "\n… result truncated at $keep of ${text.length} characters to stay within " +
            "this turn's tool-output budget. Narrow the request if you need the rest."
    }

    /**
     * What replaces an evicted result. Names the tool, because "run it again" is only
     * actionable advice if the model knows what to run — and its size, so a model that
     * decides to re-read it can narrow the call rather than reissuing the same one and
     * evicting something else.
     */
    private fun omissionNotice(r: Result): String =
        "[Earlier ${r.toolName} result (${r.text.length} characters) was omitted to stay " +
            "within this turn's tool-output budget. Call the tool again, more narrowly, " +
            "if you still need it.]"

    private fun cost(text: String): Int =
        HistoryBudget.estimateTokens(text) + PER_MESSAGE_OVERHEAD_TOKENS

    companion object {
        /**
         * Default ceiling on tool output carried within one turn.
         *
         * Sized against bounded tool results: a busy
         * `read_notifications` or a `ps -A` runs a few thousand tokens, so several
         * ordinary results coexist untouched, while a 64 KB privileged dump (~16 000)
         * is cut down — which is the case this exists for. It sits on top of
         * [HistoryBudget.DEFAULT_BUDGET_TOKENS], so the worst-case request stays the
         * same order of magnitude as the ceiling that already bounds everything else
         * rather than several times it. Revise it against the `usage` footer's real
         * figures, not by feel.
         */
        const val DEFAULT_BUDGET_TOKENS = 8_000

        /** Inverse of [HistoryBudget.estimateTokens], for going from a budget to a length. */
        private const val CHARS_PER_TOKEN = 4

        /** Matches `HistoryBudget`'s: the role, the JSON envelope, the chat separators. */
        private const val PER_MESSAGE_OVERHEAD_TOKENS = 4

        /** Headroom so the notice [fit] appends cannot itself push the result over. */
        private const val TRUNCATION_NOTICE_TOKENS = 40
    }
}
