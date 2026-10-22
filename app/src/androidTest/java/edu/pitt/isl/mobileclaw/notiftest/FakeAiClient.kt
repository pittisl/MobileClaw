package edu.pitt.isl.mobileclaw.notiftest

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A scripted [AiClient]: emits the events it was given, in order, with no HTTP and
 * no model. Where [AgentLoopTest] pins what the *client* does with a stream, this
 * pins what [MainViewModel] does with the events — the streaming flush, which turn
 * settles on disk, what an error becomes, and whether cancellation reaches the
 * producer.
 *
 * Scripting beats a live model here for the ordinary reason: a real backend can't be
 * asked to fail after exactly three tokens, and its output differs run to run.
 */
class FakeAiClient(
    private val script: suspend FlowCollector.() -> Unit
) : AiClient {

    /** The narrow surface a script needs; keeps the scripts readable. */
    interface FlowCollector {
        suspend fun emit(event: AgentEvent)
        suspend fun token(text: String) = emit(AgentEvent.TokenDelta(text))
        suspend fun done(text: String, actions: List<String> = emptyList()) =
            emit(AgentEvent.Done(ChatMessage(
                role = ChatMessage.Role.ASSISTANT, content = text, actions = actions
            )))
        /** Fails the turn with a retryable error — the shape the retry-bubble tests want. */
        suspend fun fail(message: String) =
            emit(AgentEvent.Error(AgentError(AgentError.Kind.NETWORK, message)))

        /** Fails with a specific classification, for the paths that must *not* offer a retry. */
        suspend fun fail(error: AgentError) = emit(AgentEvent.Error(error))
        suspend fun usage(promptTokens: Int, completionTokens: Int) =
            emit(AgentEvent.Usage(promptTokens, completionTokens))
    }

    /**
     * Every history the ViewModel handed over, one entry per run. Synchronized
     * because it is written from the agent coroutine and read from the test thread.
     */
    val historiesSeen: MutableList<List<ChatMessage>> =
        java.util.Collections.synchronizedList(mutableListOf())

    /** Every spec the ViewModel resolved, one entry per run. */
    val specsSeen: MutableList<AgentSpec> =
        java.util.Collections.synchronizedList(mutableListOf())

    /** Completes when the flow is collected far enough to reach [awaitStarted]. */
    private val started = CompletableDeferred<Unit>()

    /** True once the producing coroutine was cancelled — how a stop is observed. */
    @Volatile
    var wasCancelled = false
        private set

    suspend fun awaitStarted() = started.await()

    override fun run(spec: AgentSpec, history: List<ChatMessage>): Flow<AgentEvent> = flow {
        historiesSeen += history
        specsSeen += spec
        started.complete(Unit)
        val collector = object : FlowCollector {
            override suspend fun emit(event: AgentEvent) = this@flow.emit(event)
        }
        try {
            collector.script()
        } catch (c: kotlinx.coroutines.CancellationException) {
            // Cancellation propagating into the producer is the thing under test for
            // the stop button: it means the real client's HTTP call would be cancelled
            // too, rather than the UI merely ignoring a stream that keeps billing.
            wasCancelled = true
            throw c
        }
    }
}
