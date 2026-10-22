package edu.pitt.isl.mobileclaw.notiftest

import org.json.JSONArray

/**
 * Compares the request's estimated input-token cost with provider-reported usage.
 *
 * HistoryBudget, ToolOutputBudget and MemoryInjection use `chars / 4`, an English-text
 * approximation rather than a tokenizer. Actual token counts vary by provider and
 * text; underestimation lets a nominal budget admit more tokens than it claims.
 * [nonAsciiPercent] helps identify requests where that approximation is unsuitable,
 * especially CJK text.
 *
 * Only lengths and totals are logged, never prompt content. The arithmetic has no
 * Android dependency and is covered by TokenAuditTest.
 */
object TokenAudit {

    /** One message as it was actually serialised into the request body. */
    data class Message(val role: String, val serialized: String)

    /**
     * Reads the request body's `messages` array.
     *
     * Measures each message **serialised**, not its `content`. An assistant turn carrying
     * `tool_calls` has no content at all — the payload is the calls — so reading that field
     * would report the tool loop's most expensive echoes as free, which is precisely the
     * cost `ToolOutputBudget` exists to bound. Lives here rather than at the call site so
     * that trap is covered by a test.
     */
    fun from(messages: JSONArray): List<Message> =
        (0 until messages.length()).map { i ->
            val obj = messages.getJSONObject(i)
            Message(obj.optString("role"), obj.toString())
        }

    /**
     * The buckets a request divides into. They are chosen to line up with the things that
     * can be *changed*: the catalog reflects the registered tools, [history] is
     * `HistoryBudget`'s domain, and [toolOutput] is `ToolOutputBudget`'s.
     *
     * `MemoryInjection`'s block is **inside [system]** and not broken out. It is folded
     * into the system prompt by the contextProvider before the client sees it, so the seam
     * that would let this split it does not exist. Worth knowing before reading a system
     * figure as if it were the prompt alone.
     */
    data class Reading(
        val estimated: Int,
        val reported: Int,
        val catalog: Int,
        val system: Int,
        val history: Int,
        val toolOutput: Int,
        val chars: Int,
        val nonAsciiPercent: Int,
        val messages: Int
    ) {
        /**
         * How far off the estimate was, as `reported / estimated`. Above 1.0 means the
         * budgets are admitting more than they claim — the dangerous direction, and the
         * one CJK produces.
         */
        val ratio: Double get() = if (estimated <= 0) 0.0 else reported.toDouble() / estimated

        /** One grep-able line. Deliberately terse: it is written on every request. */
        fun format(): String = "est=$estimated actual=$reported ratio=${"%.2f".format(ratio)} | " +
            "catalog=$catalog sys=$system hist=$history tool=$toolOutput | " +
            "msgs=$messages chars=$chars nonAscii=$nonAsciiPercent%"
    }

    /**
     * [reportedPromptTokens] is the provider's own count for this request — the input half
     * only. Completion tokens are not audited: nothing estimates them ahead of time, so
     * there is no prediction to check.
     */
    fun measure(
        messages: List<Message>,
        catalogJson: String?,
        reportedPromptTokens: Int
    ): Reading {
        val catalog = catalogJson?.let { HistoryBudget.estimateTokens(it) } ?: 0

        var system = 0
        var history = 0
        var toolOutput = 0
        messages.forEach { m ->
            val tokens = HistoryBudget.estimateTokens(m.serialized)
            when (m.role) {
                "system" -> system += tokens
                "tool" -> toolOutput += tokens
                // user, assistant, and anything a provider adds later. Bucketed together
                // because they are what HistoryBudget selects between; splitting them
                // would invite reading one as if the budget governed it alone.
                else -> history += tokens
            }
        }

        val allText = (catalogJson ?: "") + messages.joinToString("") { it.serialized }
        return Reading(
            estimated = catalog + system + history + toolOutput,
            reported = reportedPromptTokens,
            catalog = catalog,
            system = system,
            history = history,
            toolOutput = toolOutput,
            chars = allText.length,
            nonAsciiPercent = nonAsciiPercent(allText),
            messages = messages.size
        )
    }

    /**
     * Share of characters outside ASCII, rounded down. The discriminator for a bad ratio:
     * an English-only request that bills 1.05× its estimate is the approximation working,
     * and a request at 40% non-ASCII billing 3× it is the approximation being applied to
     * text it does not describe.
     */
    private fun nonAsciiPercent(text: String): Int {
        if (text.isEmpty()) return 0
        val n = text.count { it.code > 127 }
        return (n * 100) / text.length
    }
}
