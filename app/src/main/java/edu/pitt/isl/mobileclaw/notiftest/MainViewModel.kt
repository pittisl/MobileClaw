package edu.pitt.isl.mobileclaw.notiftest

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Owns the chat/agent loop. Runs in [viewModelScope] so an in-flight turn
 * SURVIVES configuration changes (rotation) — unlike the old Activity-scoped
 * launch, which dropped the reply on rotate. The Activity only collects
 * [running] + ChatStore for display.
 */
class MainViewModel @JvmOverloads constructor(
    app: Application,
    /**
     * How the backend is built. A factory rather than a ready-made [AiClient]
     * because the client needs [buildNotifContext], which doesn't exist until
     * `this` does — a default argument can't reference an instance method.
     *
     * `@JvmOverloads` is load-bearing: without it Kotlin emits no
     * single-argument constructor and `AndroidViewModelFactory`, which reflects
     * for exactly `(Application)`, fails at runtime. Tests pass a fake here.
     */
    aiClientFactory: ((contextProvider: () -> String) -> AiClient)? = null
) : AndroidViewModel(app) {

    init {
        // Build context-dependent tools (e.g. music control) before the spec reads them.
        SkillRegistry.init(app)
        AppSettings.init(app)
    }

    private val injectedClient: AiClient? = aiClientFactory?.invoke { buildTurnContext() }

    /**
     * Resolves the conversation's agent for each turn. The session's `agentSpecId`
     * determines the agent, and [AgentRegistry.generalChat] reads current [LlmConfig]
     * settings. Holding a spec for the ViewModel's lifetime would freeze both choices.
     * The turn keeps its resolved copy so switching conversations cannot redirect it.
     */
    private fun specFor(sessionId: String): AgentSpec =
        AgentRegistry.resolve(ChatStore.agentSpecIdFor(sessionId))
            .copy(tools = ToolRegistry.all())

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    val resetting: StateFlow<Boolean> = DemoReset.resetting
    private val _demoResetCompleted = MutableStateFlow(0L)
    val demoResetCompleted: StateFlow<Long> = _demoResetCompleted
    private val _demoResetFailed = MutableStateFlow(0L)
    val demoResetFailed: StateFlow<Long> = _demoResetFailed
    private val turnJobs = java.util.Collections.synchronizedSet(mutableSetOf<Job>())
    private var currentJob: Job? = null
    private var activeRunId = 0

    fun submit(text: String) = submit(text, selectedActionContext = null)

    /** A chip belongs to its displayed source, not whichever notification arrived last. */
    fun submitAction(source: ChatMessage, action: String) {
        if (resetting.value || source !in ChatStore.messages.value) return
        val context = SelectedAction.context(source, action) ?: return
        submit(action, selectedActionContext = context)
    }

    private fun submit(text: String, selectedActionContext: String?) {
        if (resetting.value) return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        ChatStore.add(ChatMessage(role = ChatMessage.Role.USER, content = trimmed,
            selectedActionContext = selectedActionContext))
        val slot = ChatStore.add(
            ChatMessage(role = ChatMessage.Role.ASSISTANT, content = "", pending = true)
        )
        runAgent(slot)
    }

    fun retry(errorMsg: ChatMessage) {
        if (resetting.value) return
        // Match the whole message, not just its timestamp: [submit] creates the user
        // turn and the pending placeholder back to back, so they land in the same
        // millisecond and a timestamp-only lookup finds the *user's* turn first — retry
        // then overwrote the question with the new answer and left the stale error
        // bubble below it. ChatMessage is a data class, so structural equality
        // distinguishes them by role and content.
        val idx = ChatStore.messages.value.indexOfFirst { it == errorMsg }
        if (idx < 0) return
        // The retried bubble is in the conversation on screen — the only one the user can
        // have tapped a chip in.
        val slot = ChatStore.Slot(ChatStore.activeSessionId.value, idx)
        ChatStore.replaceAt(slot, ChatMessage(role = ChatMessage.Role.ASSISTANT, content = "", pending = true))
        runAgent(slot)
    }

    fun cancel() {
        currentJob?.cancel()
    }

    fun resetDemo() {
        if (resetting.value) return
        val targetSession = ChatStore.activeSessionId.value
        viewModelScope.launch {
            try {
                DemoReset.run(targetSession) {
                    // A superseded turn can still be unwinding even though currentJob changed.
                    val jobs = synchronized(turnJobs) { turnJobs.toList() }
                    jobs.forEach { it.cancel() }
                    jobs.forEach { it.join() }
                    currentJob = null
                    _running.value = false
                }
                _demoResetCompleted.value++
            } catch (e: Exception) {
                android.util.Log.e("MobileClaw-RS", "Demo reset failed", e)
                _demoResetFailed.value++
            }
        }
    }

    private fun runAgent(slot: ChatStore.Slot) {
        currentJob?.cancel()
        val runId = ++activeRunId
        _running.value = true
        // Captured before the launch, not read inside it: the user can switch
        // conversations between the two, and this turn's history is the one it was asked
        // in. [ChatStore.Slot] does the same job for where the answer lands.
        val history = ChatStore.messages.value
        // Same rule, same reason — and keyed off the slot rather than the active session,
        // because a retry can be launched for a conversation that is no longer on screen.
        val photoTurn = PhotoTurn(slot.sessionId)
        val config = LlmConfig.snapshot()
        val aiClient = injectedClient ?: LlmClients.chat(config) { buildTurnContext() }
        val baseSpec = specFor(slot.sessionId).copy(model = config.profile.model)
        val spec = baseSpec.copy(systemPrompt = baseSpec.systemPrompt + "\n\n" + photoTurn.modelContext())
        // Keep the streaming bubble's identity stable — DiffUtil keys on
        // timestampMs + role, so reusing the pending message's timestamp makes token
        // updates animate in place instead of re-adding the row each flush.
        val ts = history.getOrNull(slot.index)?.timestampMs
            ?: System.currentTimeMillis()
        currentJob = viewModelScope.launch(photoTurn) {
            val streamed = StringBuilder()
            val toolProgress = ToolProgress()
            var hasVisibleProse = false
            var lastFlushMs = 0L
            var finalMsg: ChatMessage? = null
            // Summed, not assigned: a turn that runs tools makes several requests, and
            // what the user is being billed for is the whole turn.
            var promptTokens = 0
            var completionTokens = 0
            val reply = try {
                aiClient.run(spec, history).collect { event ->
                    when (event) {
                        is AgentEvent.TokenDelta -> {
                            streamed.append(event.text)
                            val visible = StreamText.visible(streamed.toString())
                            val firstProse = !hasVisibleProse && visible.isNotBlank()
                            if (visible.isNotBlank()) hasVisibleProse = true
                            // Coalesce UI updates so the RecyclerView doesn't thrash on
                            // every token. The first visible prose immediately replaces
                            // tool progress; the final Done always flushes the full text.
                            val now = System.currentTimeMillis()
                            if (hasVisibleProse && (firstProse || now - lastFlushMs >= STREAM_FLUSH_MS)) {
                                lastFlushMs = now
                                ChatStore.replaceAt(
                                    slot,
                                    ChatMessage(
                                        role = ChatMessage.Role.ASSISTANT,
                                        content = visible,
                                        timestampMs = ts,
                                        pending = false
                                    )
                                )
                            }
                        }
                        is AgentEvent.ToolCall, is AgentEvent.ToolResult -> {
                            if (!hasVisibleProse && toolProgress.update(event)) {
                                // Pending keeps this display-only text out of history
                                // and storage. Done/error/cancellation settles the row
                                // through the settled-message finalize path.
                                ChatStore.replaceAt(
                                    slot,
                                    ChatMessage(
                                        role = ChatMessage.Role.ASSISTANT,
                                        content = toolProgress.text,
                                        timestampMs = ts,
                                        pending = true
                                    )
                                )
                            }
                        }
                        is AgentEvent.Usage -> {
                            promptTokens += event.promptTokens
                            completionTokens += event.completionTokens
                        }
                        is AgentEvent.Done  -> {
                            // Whatever we thought was wrong with the key isn't.
                            LlmConfig.noteSuccess(config.revision)
                            finalMsg = event.message.copy(timestampMs = ts)
                        }
                        is AgentEvent.Error -> {
                            // Only AUTH is worth recording: NOT_CONFIGURED is derived from
                            // the key itself, so LlmConfig already knows without being told.
                            if (event.error.kind == AgentError.Kind.AUTH) LlmConfig.noteAuthFailure(config.revision)
                            finalMsg = errorMessage(event.error).copy(timestampMs = ts)
                        }
                    }
                }
                // Attached after the collect rather than inside the Done branch, so the
                // count doesn't depend on Usage happening to arrive before Done.
                finalMsg?.copy(promptTokens = promptTokens, completionTokens = completionTokens)
                    ?: errorMessage(
                        AgentError(AgentError.Kind.UNKNOWN, "The backend returned no response.")
                    ).copy(timestampMs = ts)
            } catch (_: CancellationException) {
                errorMessage(AgentError.cancelled()).copy(timestampMs = ts)
            } catch (t: Throwable) {
                // Backstop only: the client classifies its own failures and emits them as
                // AgentEvent.Error, so anything reaching here is a bug rather than a
                // transport problem. Classified anyway so the bubble looks like the others.
                errorMessage(AgentError.classify(t)).copy(timestampMs = ts)
            }
            // finalize, not replaceAt: this is the settled turn, the one worth storing.
            // The mid-stream replaceAt calls above stay in memory by design.
            ChatStore.finalize(slot, reply.copy(photos = photoTurn.candidates))
            if (runId == activeRunId) _running.value = false
        }.also { job ->
            turnJobs.add(job)
            job.invokeOnCompletion { turnJobs.remove(job) }
        }
    }

    /**
     * The error bubble. [AgentError.retryable] rides along so the adapter can withhold
     * the Retry chip for the failures a retry cannot fix — a missing key, a rejected
     * one, a request the provider will reject identically next time.
     */
    private fun errorMessage(e: AgentError): ChatMessage =
        ChatMessage(
            role = ChatMessage.Role.ASSISTANT,
            content = e.message,
            isError = true,
            retryable = e.retryable
        )

    /**
     * Everything about the live device that gets folded into the system prompt each turn:
     * what is on screen now, and what the agent knows about the user.
     *
     * Both land in the system prompt rather than as `role:"system"` turns, per the
     * standing convention, and both are therefore charged against [HistoryBudget] — the
     * client estimates the assembled prompt before it selects history, so a busy shade or
     * a full memory store costs conversation turns instead of quietly overrunning.
     *
     * **This is the chat client's context provider and nothing else's.** In particular
     * [ReasoningModule] builds its own prompt and does not come through here, which is
     * what keeps the chat-only injection rule true: the triage loop runs once per *notification*, so
     * anything added to it is multiplied by the notification rate. Memory belongs to the
     * conversation, whose rate the user sets.
     */
    private fun buildTurnContext(): String = listOfNotNull(
        // Whole seconds avoid nanosecond digits being mistaken for an OTP by redaction.
        "Current device time: ${java.time.ZonedDateTime.now().withNano(0).format(java.time.format.DateTimeFormatter.ISO_ZONED_DATE_TIME)}",
        buildNotifContext(),
        MemoryInjection.buildBlock(MemoryStore.memories.value)
    ).joinToString("\n\n")

    /**
     * Compact text block of active notifications, injected into the system prompt
     * each turn. Redacted via [ReasoningModule.redact] so sensitive data never
     * leaves the device through chat context.
     */
    private fun buildNotifContext(): String {
        val active = NotifStore.events.value.filter { it.posted }
        if (active.isEmpty()) return "No notifications are currently captured."

        val fmt = SimpleDateFormat("HH:mm", Locale.US)
        val lines = active.asReversed().take(25).mapIndexed { i, n ->
            buildString {
                append("  [${i + 1}] ${fmt.format(Date(n.postTimeMs))} · ${n.appName}")
                if (n.title.isNotBlank()) append(" — ${ReasoningModule.redact(n.title)}")
                val body = ReasoningModule.redact(n.text.take(120).replace('\n', ' '))
                if (body.isNotBlank()) append(": $body")
                if (n.actions.isNotEmpty()) append("  (${n.actions.joinToString(", ") { it.title }})")
                if (n.isOngoing) append("  [ongoing]")
                if (n.progressMax > 0) append("  [${n.progressCurrent}/${n.progressMax}]")
            }
        }.joinToString("\n")

        return "Captured notifications (${active.size} total, newest first):\n$lines"
    }

    private companion object {
        /** Min gap between mid-stream UI flushes (~20 fps) to avoid RecyclerView thrash. */
        const val STREAM_FLUSH_MS = 50L
    }
}
