package edu.pitt.isl.mobileclaw.notiftest

/**
 * A single turn in the AI chat panel. The role determines how the bubble is
 * rendered (user on the right, assistant on the left) and what we send to the
 * future LLM bridge as the message author.
 */
data class ChatMessage(
    val role: Role,
    val content: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val pending: Boolean = false,
    val isError: Boolean = false,
    /**
     * Whether this error bubble should offer a Retry chip. Only meaningful with
     * [isError], and false by default so a failure nobody classified doesn't
     * accidentally promise a recovery: the classification in [AgentError.retryable] is
     * what turns it on. A retry offered for a missing API key re-fails identically and
     * teaches the user the button is decorative.
     */
    val retryable: Boolean = false,
    val actions: List<String> = emptyList(),  // tappable buttons rendered below the bubble
    /**
     * Tokens the provider billed for the turn that produced this message, summed
     * across every request the tool loop made. Surfaced under the bubble so a prompt
     * quietly growing is visible while it happens rather than on the invoice.
     *
     * Memory-only, like [pending] and [isError]: persisting it would mean a schema
     * migration for a diagnostic, so a restored conversation shows no footer.
     */
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    /** Actual local search results, not model-supplied image paths. Re-search after process death. */
    val photos: List<PhotoCandidate> = emptyList(),
    /**
     * Quoted source for a clicked action, passed separately from USER text. Transient:
     * each chip click rebuilds it from the source bubble, which is already persisted.
     * Retried turns retain it in memory; no pending turn resumes after process death.
     */
    val selectedActionContext: String? = null
) {
    enum class Role { USER, ASSISTANT, SYSTEM }

    val hasUsage: Boolean get() = promptTokens > 0 || completionTokens > 0
}
