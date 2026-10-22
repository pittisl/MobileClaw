package edu.pitt.isl.mobileclaw.notiftest

/**
 * Chooses which conversation turns actually go into a request.
 *
 * Without a history budget, every turn re-sends everything before it, so
 * input tokens grow linearly with the session and total spend grows *quadratically*.
 * `ChatStore.MAX_ENTRIES` bounds the in-memory entry count, but counting turns is the wrong
 * unit — 100 one-line turns and 100 turns carrying tool output differ by an order of
 * magnitude. This bounds the thing that is actually billed.
 *
 * Three rules, in priority order:
 *
 *  1. **The newest turn always goes**, even alone over budget. It is the question
 *     being asked; a request without it is worse than an expensive one.
 *  2. **The first user turn is pinned** when it fits. A pure sliding window drops the
 *     original request, which in an agent session is usually the most load-bearing
 *     thing said — "find my flight confirmation and add it to my calendar" is what
 *     every later turn is in service of.
 *  3. **Everything else fills backwards from the newest** until the budget runs out.
 *
 * That leaves at most one hole, between the pinned head and the resumed tail, which
 * [Window.gapAfterIndex] locates so the caller can say so in the prompt rather than
 * silently presenting two distant turns as adjacent.
 *
 * No Android imports — this is where the hostile-arithmetic cases get tested
 * (`HistoryBudgetTest`) with no device, the same reasoning as [FsPolicy].
 */
internal object HistoryBudget {

    /**
     * Default per-request ceiling on history + system prompt + tool catalog.
     *
     * A conservative budget for long-context models
     * like `gemini-2.5-flash`: comfortably fits a long working session while keeping
     * the per-turn input bill bounded and predictable. It is a budget, not a model
     * limit — raise it deliberately, ideally against the real numbers the `usage`
     * footer reports.
     */
    const val DEFAULT_BUDGET_TOKENS = 30_000

    /**
     * Rough token count. `chars / 4` is the standard English approximation and is
     * deliberately crude: the point is to compare messages against each other, and a
     * real tokenizer would mean shipping vocab files to do the same job. The provider's
     * reported `usage` is the ground truth we correct against — see [AgentEvent.Usage].
     */
    fun estimateTokens(text: String): Int = (text.length + 3) / 4

    /**
     * Per-message cost that isn't its text: the role, the JSON envelope, and the
     * chat-template separators the server adds. Small, but it's what stops a history of
     * hundreds of two-word turns from being counted as free.
     */
    private const val PER_MESSAGE_OVERHEAD_TOKENS = 4

    /** The turns that may be sent at all: settled USER/ASSISTANT conversation. */
    private fun eligible(history: List<ChatMessage>): List<ChatMessage> = history.filter {
        !it.pending && !it.isError &&
            (it.role == ChatMessage.Role.USER || it.role == ChatMessage.Role.ASSISTANT)
    }

    private fun cost(m: ChatMessage): Int = estimateTokens(m.content) + PER_MESSAGE_OVERHEAD_TOKENS

    /**
     * The selected turns, in chronological order, plus what it cost and what was left
     * out.
     *
     * @param gapAfterIndex index *within [messages]* after which turns were dropped,
     *   or -1 when the window is contiguous.
     */
    data class Window(
        val messages: List<ChatMessage>,
        val dropped: Int,
        val estimatedTokens: Int,
        val gapAfterIndex: Int
    )

    /**
     * @param budgetTokens total ceiling for the request.
     * @param reservedTokens what the request already spends before any history — the
     *   system prompt (which carries the live notification context) and the tool
     *   catalog. Passing it in rather than assuming a fixed overhead means a turn with
     *   30 notifications on screen automatically gets a shorter history instead of
     *   quietly blowing the budget.
     */
    fun select(
        history: List<ChatMessage>,
        budgetTokens: Int = DEFAULT_BUDGET_TOKENS,
        reservedTokens: Int = 0
    ): Window {
        val turns = eligible(history)
        if (turns.isEmpty()) return Window(emptyList(), 0, 0, -1)

        val budget = (budgetTokens - reservedTokens).coerceAtLeast(0)

        val newest = turns.lastIndex
        val keep = sortedSetOf(newest)
        var used = cost(turns[newest]) // rule 1: unconditional, budget or not

        // Rule 2: pin the opening request, but never at the cost of rule 1.
        val head = turns.indexOfFirst { it.role == ChatMessage.Role.USER }
        if (head >= 0 && head != newest && used + cost(turns[head]) <= budget) {
            keep += head
            used += cost(turns[head])
        }

        // Rule 3: fill backwards. Stop at the first turn that doesn't fit rather than
        // hunting for smaller ones further back — skipping around would scatter the
        // conversation into holes the model has to guess at, and the recent turns are
        // the ones that carry the thread anyway.
        for (i in newest - 1 downTo 0) {
            if (i in keep) continue
            val c = cost(turns[i])
            if (used + c > budget) break
            keep += i
            used += c
        }

        val kept = keep.toList()
        val gap = kept.indices.firstOrNull { it < kept.lastIndex && kept[it + 1] != kept[it] + 1 } ?: -1
        return Window(
            messages = kept.map { turns[it] },
            dropped = turns.size - kept.size,
            estimatedTokens = used,
            gapAfterIndex = gap
        )
    }

    /**
     * How to describe the trim in the system prompt, or null when nothing was dropped.
     *
     * A marker, not a summary: summarising evicted turns needs another model call, whereas saying plainly that a hole exists costs ~30 tokens and stops
     * the model from reading the pinned first turn and the recent tail as consecutive.
     *
     * It goes in the **system prompt** rather than as a `role:"system"` turn in the
     * middle of the array — the same call the client already makes for background
     * decisions. Not every OpenAI-compatible gateway handles a non-leading system
     * message the way OpenAI does, and this is a footnote, not a turn.
     */
    fun trimNotice(window: Window): String? {
        if (window.dropped == 0) return null
        val where = if (window.gapAfterIndex >= 0) {
            "between the first turn and the recent ones"
        } else {
            "at the start of the conversation"
        }
        return "History note: ${window.dropped} earlier turn(s) $where were omitted to stay " +
            "within the context budget. Ask the user if you need something from that gap."
    }
}
