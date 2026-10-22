package edu.pitt.isl.mobileclaw.notiftest

import org.json.JSONObject

/**
 * A capability the agent can call mid-turn. The model is handed [name],
 * [description] and [parameters] (a JSON-Schema object); when it decides to call
 * the tool, [invoke] runs and its String result is fed back into the loop so the
 * model can continue with the answer.
 */
interface Tool {
    val name: String
    val description: String
    val parameters: JSONObject
    /** Buffer model prose when this tool can return a deferred execution receipt. */
    val requiresConfirmationReceipt: Boolean get() = false
    /** Invalidate turn-local output before parsing a new invocation, even malformed JSON. */
    fun onInvocationStarted() = Unit
    suspend fun invoke(args: JSONObject): String
    suspend fun invokeWithReceipt(args: JSONObject): ToolInvocation = ToolInvocation.Text(invoke(args))
}

/** Execution state comes from the app, never inferred from model or tool-result prose. */
sealed class ToolInvocation(val text: String) {
    class Text(text: String) : ToolInvocation(text)
    class AwaitingConfirmation(val description: String, text: String) : ToolInvocation(text)
    class NotExecuted(text: String) : ToolInvocation(text)
}
