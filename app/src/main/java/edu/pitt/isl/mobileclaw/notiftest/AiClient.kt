package edu.pitt.isl.mobileclaw.notiftest

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Backend contract for incremental assistant output, tool execution and token usage.
 * Clients emit [AgentEvent] values through a Flow so the UI can render a turn while
 * it runs and handle a classified failure without losing already-rendered content.
 */
interface AiClient {
    fun run(spec: AgentSpec, history: List<ChatMessage>): Flow<AgentEvent>
}

class StubAiClient : AiClient {
    override fun run(spec: AgentSpec, history: List<ChatMessage>): Flow<AgentEvent> = flow {
        delay(400)
        val lastUser = history.lastOrNull { it.role == ChatMessage.Role.USER }?.content
        val text = if (lastUser.isNullOrBlank()) "(AI bridge not connected yet.)"
                   else "(echo: \"$lastUser\")"
        emit(AgentEvent.Done(ChatMessage(role = ChatMessage.Role.ASSISTANT, content = text)))
    }
}

/**
 * Calls an OpenAI-compatible endpoint with `stream: true` and emits assistant
 * tokens as [AgentEvent.TokenDelta] while they arrive (SSE), then a final
 * [AgentEvent.Done]. Tool calls stream in fragments, are reassembled by index, and
 * drive the tool-execution loop — the user sees one continuous
 * assistant bubble across multiple round-trips.
 *
 * The reply streams as plain markdown (no JSON envelope); optional quick-reply
 * chips are carried on a trailing [ACTIONS_SENTINEL] line, parsed out in
 * [finalMessage]. Transient failures are retried with backoff via [withRetry], but
 * only around opening the stream — never mid-stream, which would re-bill and
 * duplicate tokens.
 */
class OpenAiAiClient(
    private val apiKey: String,
    private val endpoint: String = "https://api.openai.com/v1/chat/completions",
    private val contextProvider: (() -> String)? = null,
    /** Per-request input ceiling; see [HistoryBudget]. Injectable so tests can shrink it. */
    private val budgetTokens: Int = HistoryBudget.DEFAULT_BUDGET_TOKENS,
    /** Ceiling on tool output carried within one turn; see [ToolOutputBudget]. */
    private val toolBudgetTokens: Int = ToolOutputBudget.DEFAULT_BUDGET_TOKENS,
    /**
     * Gap allowed between bytes of the stream. 60 s is generous for a cloud provider,
     * whose first token arrives in a second or two; a model running on the phone spends
     * that long reading the tool catalog before it emits anything, so the
     * local path passes a larger value rather than the constant being raised for all.
     */
    private val readTimeoutSeconds: Long = 60,
    private val auth: LlmAuth = LlmAuth.BEARER,
    private val enforceInputBudget: Boolean = false,
) : AiClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /**
     * Whether to ask for token usage on the stream. `stream_options` is an OpenAI
     * extension that some compatible gateways reject. A 400 response disables it for
     * this client's lifetime and retries without it only when the response body names
     * that field. An unrelated bad request must not disable usage reporting.
     */
    @Volatile
    private var includeUsageOptions = true

    private fun buildSystemPrompt(spec: AgentSpec): String {
        val ctx = contextProvider?.invoke()?.takeIf { it.isNotBlank() } ?: return spec.systemPrompt
        return "${spec.systemPrompt}\n\n$ctx"
    }

    override fun run(spec: AgentSpec, history: List<ChatMessage>): Flow<AgentEvent> = flow {
        // Emitted, not thrown: running an unconfigured build is a normal outcome, not an
        // exceptional one, and routing it through the generic failure path is what made
        // it indistinguishable from a dropped connection.
        if ((auth == LlmAuth.BEARER && apiKey.isBlank()) || endpoint.isBlank() || spec.model.isBlank()) {
            emit(AgentEvent.Error(AgentError.notConfigured()))
            return@flow
        }

        // Fold any background [Auto] decisions (SYSTEM role) into the system prompt
        // so the model has context without polluting the JSON conversation turns.
        val bgDecisions = history
            .filter { it.role == ChatMessage.Role.SYSTEM && !it.pending }
            .takeLast(8)
        val backgroundPrompt = if (bgDecisions.isEmpty()) {
            buildSystemPrompt(spec)
        } else {
            val block = bgDecisions.joinToString("\n") { it.content }
            buildSystemPrompt(spec) + "\n\nBackground decisions already made:\n$block"
        }

        // The selected bubble is explicit context, never pasted into the user's words.
        // Keep it in the system reserve so history trimming cannot drop the chip's source.
        val latestUser = history.lastOrNull { it.role == ChatMessage.Role.USER && !it.pending }
        val systemPrompt = latestUser?.selectedActionContext?.let {
            backgroundPrompt + "\n\n" + SelectedAction.INSTRUCTIONS + "\n" + it
        } ?: backgroundPrompt

        val toolsJson = spec.tools.takeIf { it.isNotEmpty() }?.let { buildToolsJson(it) }

        // The turn's user message, for tools whose behaviour depends on what the *user*
        // said rather than on what the model passed them — today only memory.
        // Taken from the newest USER turn, not from anywhere in the history: a question
        // asked three days ago is not consent to store a secret now, and the model
        // restating an old message is model output, not user input. Installed around
        // tool invocation only, so nothing else in the loop can see it.
        val agentTurn = latestUser
            ?.content
            ?.takeIf { it.isNotBlank() }
            ?.let { AgentTurn(UserTurnEvidence(it)) }

        // Only settled USER / ASSISTANT turns are eligible, and only as many of them as
        // the budget allows. The system prompt and tool catalog are charged against the
        // same budget rather than assumed away — the prompt carries the live
        // notification context, so it is not a small constant.
        val reserved = HistoryBudget.estimateTokens(systemPrompt) +
            (toolsJson?.let { HistoryBudget.estimateTokens(it.toString()) } ?: 0)
        val window = HistoryBudget.select(history, budgetTokens, reserved)

        // The trim notice rides in the system prompt, like the background decisions
        // above — a footnote about the history, not a turn in it. Its ~30 tokens are
        // not charged against [reserved]; they land after the window is already chosen,
        // which is deliberate: re-selecting to account for the notice could change
        // whether there *is* one.
        val trimNotice = HistoryBudget.trimNotice(window)
        val fullSystemPrompt = if (trimNotice == null) systemPrompt else "$systemPrompt\n\n$trimNotice"

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", fullSystemPrompt))
        window.messages.forEach { msg ->
            val role = if (msg.role == ChatMessage.Role.USER) "user" else "assistant"
            messages.put(JSONObject().put("role", role).put("content", msg.content))
        }
        if (window.dropped > 0) {
            Log.i(TAG, "history trimmed: ${window.messages.size} turns kept, ${window.dropped} dropped, " +
                "~${window.estimatedTokens} history + ~$reserved reserved of $budgetTokens tokens")
        }

        // The readable trace. Emitted before the request
        // rather than after it, so a turn that dies mid-stream still leaves behind the
        // context it was reasoning over — which is the turn you most want to look at.
        val traceTurn = ReasoningTrace.chatTurnStart(
            sessionTitle = spec.displayName,
            systemPrompt = fullSystemPrompt,
            history = window.messages,
            toolCount = ReasoningTrace.toolCount(toolsJson),
            droppedTurns = window.dropped
        )

        // Agent loop: stream the model's reply token-by-token; if it requests tools,
        // run them and loop, streaming the next turn into the SAME assistant bubble.
        //
        // The budget is per-invocation, not per-client: it bounds what one turn's loop
        // accumulates, and the next turn starts from nothing because tool results don't
        // outlive the call. [toolMessages] holds the same JSONObjects that are already in
        // [messages], so rewriting one there rewrites what gets sent.
        val toolBudget = ToolOutputBudget(toolBudgetTokens)
        val toolMessages = mutableListOf<JSONObject>()
        // A response may stream "I've added it" before its tool_calls fragments.
        // With AppFunctions available, withhold prose until execution state is known.
        // Plain read-only catalogs retain normal streaming. A completion-gated
        // draft also withholds interim prose until its required preparation finishes.
        val bufferProse = spec.tools.any { it.requiresConfirmationReceipt } || spec.completionReminder != null
        var iterations = 0
        var completingRequirement = false
        while (true) {
            iterations++
            val requiredTool = if (completingRequirement) spec.completionToolChoice?.invoke() else null
            require(requiredTool == null || spec.tools.any { it.name == requiredTool }) {
                "Completion tool must be in this turn's catalog"
            }

            if (enforceInputBudget && HistoryBudget.estimateTokens(messages.toString()) +
                (toolsJson?.let { HistoryBudget.estimateTokens(it.toString()) } ?: 0) > budgetTokens) {
                emit(AgentEvent.Error(AgentError(AgentError.Kind.BAD_REQUEST,
                    "This turn exceeds the configured input budget. Use a model with a larger context " +
                        "and adjust Settings > Model service, or start a shorter conversation.")))
                return@flow
            }

            // Retry ONLY wraps opening the stream (connect + status). Once tokens
            // start flowing we never retry — that would re-bill and duplicate output.
            val response = withRetry {
                try {
                    openStream(buildBody(spec, messages, toolsJson, includeUsageOptions, requiredTool))
                } catch (e: UnsupportedOptionException) {
                    Log.w(TAG, "provider rejected stream_options (HTTP ${e.code}); dropping usage reporting")
                    includeUsageOptions = false
                    openStream(buildBody(spec, messages, toolsJson, includeUsage = false, requiredTool = requiredTool))
                }
            }

            val turn = SseTurn()
            val rawBody = StringBuilder() // non-SSE lines, for the ignored-stream fallback

            response.use { resp ->
                val source = resp.body!!.source()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isEmpty()) continue
                    if (!line.startsWith("data:")) { rawBody.append(line); continue }
                    val payload = line.substring(5).trim()
                    if (payload == "[DONE]") break
                    val frag = turn.onPayload(payload)
                    if (frag.isNotEmpty() && !bufferProse) emit(AgentEvent.TokenDelta(frag))
                }
            }

            // Fallback: the provider ignored stream:true and returned one completion.
            if (turn.content.isEmpty() && !turn.hasToolCalls() && rawBody.isNotEmpty()) {
                val frag = turn.fallbackFromWholeBody(rawBody.toString())
                if (frag.isNotEmpty() && !bufferProse) emit(AgentEvent.TokenDelta(frag))
            }
            Log.i(TAG, "stream turn done: finish=${turn.finishReason}, ${turn.content.length} chars, tools=${turn.hasToolCalls()}")

            // Per-request, not per-turn: a tool loop makes several requests and the
            // collector sums them. Emitted before Done so a turn's total is complete by
            // the time the final message lands.
            if (turn.promptTokens > 0 || turn.completionTokens > 0) {
                Log.i(TAG, "usage: ${turn.promptTokens} in / ${turn.completionTokens} out")
                // Compare each request with the history, memory and daily-pass budget estimates:
                // all three budgets are denominated in an unverified `chars / 4`. Logged
                // rather than collected into a screen, so the sample accumulates from
                // ordinary use on whatever device is running — the alternative was a
                // measurement session reading footers off bubbles, and `promptTokens` is
                // memory-only, so that sample dies with the process.
                Log.i(TOKEN_TAG, TokenAudit.measure(
                    messages = TokenAudit.from(messages),
                    catalogJson = toolsJson?.toString(),
                    reportedPromptTokens = turn.promptTokens
                ).format())
                emit(AgentEvent.Usage(turn.promptTokens, turn.completionTokens))
            }

            val hasToolCalls = toolsJson != null && turn.hasToolCalls()
            if (hasToolCalls && iterations <= MAX_TOOL_ITERATIONS) {
                messages.put(turn.assistantTurnJson()) // echo the assistant turn (carries tool_calls)
                val receipts = mutableListOf<ToolInvocation>()
                for ((id, name, argsStr) in turn.toolCalls()) {
                    Log.i(TAG, "tool_call: $name $argsStr")
                    ReasoningTrace.toolCall(traceTurn, name, argsStr)
                    emit(AgentEvent.ToolCall(id, name, argsStr))
                    val invocation = invokeTool(spec.tools, name, argsStr, agentTurn)
                    if (invocation !is ToolInvocation.Text) receipts += invocation
                    val result = invocation.text
                    Log.i(TAG, "tool_result: $name -> ${result.take(160).replace("\n", " | ")}")
                    // The trace carries the whole result; the line above is capped at 160
                    // chars for the engineering log, which is a different audience.
                    ReasoningTrace.toolResult(traceTurn, name, result)
                    // The UI sees the whole result; the budget only governs what is
                    // re-sent to the model on every subsequent iteration.
                    emit(AgentEvent.ToolResult(id, result))

                    // The message is put in the array before it has content: admitting
                    // this result is what may shrink the earlier ones, so every content
                    // is written from one rewrite pass rather than two code paths.
                    val toolMsg = JSONObject().put("role", "tool").put("tool_call_id", id)
                    messages.put(toolMsg)
                    toolMessages += toolMsg
                    toolBudget.admit(name, result).forEachIndexed { i, text ->
                        toolMessages[i].put("content", text)
                    }
                }
                if (receipts.isNotEmpty()) {
                    // No model summary can establish completion. The actual confirm
                    // callback will publish the executor's return value separately.
                    val text = receipts.joinToString("\n\n") {
                        when (it) {
                            is ToolInvocation.AwaitingConfirmation ->
                                "Waiting for confirmation. Execution has not been confirmed.\n${it.description}"
                            else -> "Not executed: ${it.text}"
                        }
                    }
                    ReasoningTrace.chatTurnEnd(traceTurn, "confirmation", text, turn.promptTokens, turn.completionTokens)
                    emit(AgentEvent.Done(ChatMessage(role = ChatMessage.Role.ASSISTANT, content = text)))
                    return@flow
                }
                // loop again with the tool results appended
            } else if (hasToolCalls) {
                // Left the loop because of the cap, not because the model was finished.
                // Worth distinguishing: a capped turn usually carries tool calls and no
                // prose, and routing empty text through finalMessage produced
                // "(no response)" with isError set — a working agent reported as broken,
                // offering a retry that would loop exactly the same way, and never
                // persisted, since error bubbles aren't.
                Log.i(TAG, "tool loop hit the $MAX_TOOL_ITERATIONS-iteration cap")
                ReasoningTrace.chatTurnEnd(
                    traceTurn, "capped", turn.content.toString(), turn.promptTokens, turn.completionTokens)
                emit(AgentEvent.Done(cappedMessage(if (bufferProse) "" else turn.content.toString()), isComplete = false))
                return@flow
            } else {
                val reminder = if (turn.finishReason == "stop") spec.completionReminder?.invoke() else null
                if (reminder != null) {
                    if (iterations > MAX_TOOL_ITERATIONS) {
                        val incomplete = ChatMessage(
                            role = ChatMessage.Role.ASSISTANT,
                            content = "I couldn't complete the required preparation within this turn.",
                        )
                        ReasoningTrace.chatTurnEnd(traceTurn, "incomplete", incomplete.content, turn.promptTokens, turn.completionTokens)
                        emit(AgentEvent.Done(incomplete, isComplete = false))
                        return@flow
                    }
                    // This is an application requirement, not a new USER message or
                    // permission from notification text. Retain the original fixed seed.
                    messages.put(turn.assistantTurnJson())
                    messages.getJSONObject(0).put("content", "$fullSystemPrompt\n\nCompletion requirement:\n$reminder")
                    completingRequirement = true
                    ReasoningTrace.chatTurnEnd(traceTurn, "continue", turn.content.toString(), turn.promptTokens, turn.completionTokens)
                    continue
                }
                if (bufferProse && turn.content.isNotEmpty()) emit(AgentEvent.TokenDelta(turn.content.toString()))
                ReasoningTrace.chatTurnEnd(
                    traceTurn, turn.finishReason, turn.content.toString(),
                    turn.promptTokens, turn.completionTokens)
                emit(AgentEvent.Done(finalMessage(turn.content.toString()), isComplete = turn.finishReason == "stop"))
                return@flow
            }
        }
    }.catch { t ->
        // Classified here rather than at the collector: this is the layer that knows the
        // endpoint, so a DNS failure can name the host it couldn't resolve instead of
        // reaching the UI as a generic IOException.
        if (t is CancellationException) throw t
        Log.w(TAG, "turn failed: ${t.javaClass.simpleName}")
        val error = AgentError.classify(t, endpoint)
        emit(AgentEvent.Error(error.copy(message = error.message.withoutCredential(apiKey))))
    }.flowOn(Dispatchers.IO)

    private fun buildBody(
        spec: AgentSpec,
        messages: JSONArray,
        toolsJson: JSONArray?,
        includeUsage: Boolean,
        requiredTool: String? = null,
    ): String {
        val bodyObj = JSONObject()
            .put("model", spec.model)
            .put("messages", messages)
            .put("temperature", spec.temperature)
            .put("stream", true)
        if (toolsJson != null) bodyObj.put("tools", toolsJson)
        if (requiredTool != null) bodyObj.put("tool_choice", JSONObject()
            .put("type", "function").put("function", JSONObject().put("name", requiredTool)))
        if (includeUsage) {
            bodyObj.put("stream_options", JSONObject().put("include_usage", true))
        }
        return ChatRequestOptions.applyTo(endpoint, bodyObj).toString()
    }

    /**
     * Opens the SSE stream, translating failures into what the retry layer expects.
     * Throws [UnsupportedOptionException] on the status codes a gateway uses to reject
     * a request it can't parse, so the caller can drop `stream_options` and try again.
     */
    private suspend fun openStream(body: String): Response {
        val request = Request.Builder()
            .url(endpoint)
            .apply { if (auth == LlmAuth.BEARER) header("Authorization", "Bearer $apiKey") }
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody(jsonMedia))
            .build()
        val resp = http.newCall(request).executeCancellable()
        if (!resp.isSuccessful) {
            val code = resp.code
            // Read further than the 200 chars shown: a JSON error can name the field late.
            val errFull = resp.body?.string().orEmpty().withoutCredential(apiKey).take(4000)
            val errBody = errFull.take(200)
            // Read before close: a 429 that outlives our retries is the one case where
            // the user can be told how long to wait rather than just that they waited.
            val retryAfter = resp.header("Retry-After")?.trim()?.toIntOrNull()
            resp.close()
            // Retry transient errors (e.g. 503 overload); fail fast on the rest.
            if (isTransientHttp(code)) throw RetryableHttpException(code, "HTTP $code", retryAfter)
            if (includeUsageOptions && code in REQUEST_REJECTED_CODES && namesUsageOption(errFull)) {
                throw UnsupportedOptionException(code, "HTTP $code: $errBody")
            }
            // Typed, so the status survives to AgentError.classify — a 401 and a 400 need
            // different answers from the UI, and a flattened message can't carry that.
            throw HttpStatusException(code, errBody)
        }
        return resp
    }

    /**
     * A request the server refused to parse, while we were sending an optional field.
     * Carries the original message so that if the *second* attempt fails too — i.e. the
     * option was never the problem — the user sees the real error, not this guess.
     */
    private class UnsupportedOptionException(val code: Int, message: String) : Exception(message)

    /**
     * Turns the fully-streamed assistant text into a final [ChatMessage], splitting
     * off any trailing [ACTIONS_SENTINEL] line into quick-reply chips.
     */
    private fun finalMessage(raw: String): ChatMessage {
        if (raw.isBlank()) {
            return ChatMessage(role = ChatMessage.Role.ASSISTANT, content = "(no response)", isError = true)
        }
        val (text, actions) = StreamText.split(raw)
        return ChatMessage(role = ChatMessage.Role.ASSISTANT, content = text, actions = actions)
    }

    /**
     * Final message for a turn that ran out of tool iterations rather than finishing.
     *
     * Not an error: the model was answering, it just never stopped asking for tools.
     * Saying so plainly beats "(no response)" — and keeping [ChatMessage.isError] off
     * means the turn is persisted like any other, so the history doesn't develop a
     * hole exactly where the agent was working hardest.
     */
    private fun cappedMessage(raw: String): ChatMessage {
        val note = "(Stopped after $MAX_TOOL_ITERATIONS tool steps. Ask me to continue " +
            "if you want me to keep going.)"
        if (raw.isBlank()) {
            return ChatMessage(
                role = ChatMessage.Role.ASSISTANT,
                content = "I worked through $MAX_TOOL_ITERATIONS tool steps without " +
                    "reaching an answer. Ask me to continue and I'll pick up from here."
            )
        }
        // The model streamed prose alongside its tool calls — that is a real partial
        // answer, so keep it and append the reason it stops here.
        val (text, actions) = StreamText.split(raw)
        return ChatMessage(
            role = ChatMessage.Role.ASSISTANT,
            content = "$text\n\n$note",
            actions = actions
        )
    }

    /** Renders the agent's tools into OpenAI function-calling JSON schema. */
    private fun buildToolsJson(tools: List<Tool>): JSONArray {
        val arr = JSONArray()
        tools.forEach { t ->
            arr.put(
                JSONObject().put("type", "function").put(
                    "function",
                    JSONObject()
                        .put("name", t.name)
                        .put("description", t.description)
                        .put("parameters", t.parameters)
                )
            )
        }
        return arr
    }

    /**
     * Runs one tool with [turn] in scope, so a tool that needs to know what the user
     * actually typed can ask instead of trusting its own arguments. [turn] is null when
     * the history carried no usable user message, and every consumer treats that as
     * "no evidence" rather than as "unrestricted" — see [AgentTurn].
     */
    private suspend fun invokeTool(
        tools: List<Tool>,
        name: String,
        argsJson: String,
        turn: AgentTurn?
    ): ToolInvocation {
        val tool = tools.firstOrNull { it.name == name }
            ?: return ToolInvocation.Text("Error: unknown tool \"$name\".")
        return try {
            tool.onInvocationStarted()
            val args = if (argsJson.isBlank()) JSONObject() else JSONObject(argsJson)
            if (turn == null) tool.invokeWithReceipt(args) else withContext(turn) { tool.invokeWithReceipt(args) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val text = "Error running $name: ${e.message}"
            if (tool.requiresConfirmationReceipt) ToolInvocation.NotExecuted(text) else ToolInvocation.Text(text)
        }
    }

    companion object {
        private const val TAG = "MobileClaw-AI"

        /** Its own tag so a measurement pass is one `adb logcat -s` away from a clean sample. */
        private const val TOKEN_TAG = "MobileClaw-TK"

        private const val MAX_TOOL_ITERATIONS = 5

        /**
         * Status codes that mean "I couldn't parse that request". 404 is in here
         * because some gateways answer an unrecognised field on a known route that way
         * rather than with a 400.
         */
        private val REQUEST_REJECTED_CODES = setOf(400, 404, 422)

        /** Whether a rejection is about the optional field rather than the request itself. */
        internal fun namesUsageOption(body: String): Boolean =
            body.contains("stream_options", ignoreCase = true) || body.contains("include_usage", ignoreCase = true)
    }
}
