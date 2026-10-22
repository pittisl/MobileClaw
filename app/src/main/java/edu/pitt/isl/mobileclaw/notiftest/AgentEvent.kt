package edu.pitt.isl.mobileclaw.notiftest

/**
 * Incremental events emitted by an agent run: assistant tokens, tool calls,
 * tool results, usage, completion and classified errors.
 */
sealed class AgentEvent {
    /** Streaming assistant tokens. */
    data class TokenDelta(val text: String) : AgentEvent()

    /** The model requested a tool. */
    data class ToolCall(val id: String, val name: String, val argsJson: String) : AgentEvent()

    /** Result of running a tool, fed back into the loop. */
    data class ToolResult(val id: String, val result: String) : AgentEvent()

    /**
     * Tokens billed for **one** request, as the provider reported them. A turn that
     * runs tools makes several requests, so these are per-request deltas and the
     * collector sums them — that sum is what a turn actually cost.
     *
     * Emitted only when the provider reports usage; not every OpenAI-compatible
     * gateway does.
     */
    data class Usage(val promptTokens: Int, val completionTokens: Int) : AgentEvent()

    /** Turn finished cleanly with a final assistant message. */
    /** The visible answer may be partial; autonomous proposals require a completed turn. */
    data class Done(val message: ChatMessage, val isComplete: Boolean = true) : AgentEvent()

    /**
     * Turn aborted, carrying an [AgentError] classification that tells the UI which
     * recovery actions can succeed. Missing configuration and a network interruption
     * must not offer the same Retry action.
     */
    data class Error(val error: AgentError) : AgentEvent()
}
