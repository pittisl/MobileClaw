package edu.pitt.isl.mobileclaw.notiftest

import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The current turn, carried to the tools that run inside it.
 *
 * `Tool.invoke(args)` sees only what the *model* wrote, which is exactly right for a tool
 * and exactly wrong for [MemoryPolicy]: the user-attested redaction exception turns on what the **user** typed,
 * and a fact that arrives as a tool argument is a fact a hostile notification can talk the
 * model into supplying. So the turn's evidence takes a path the model cannot write to —
 * [OpenAiAiClient] installs this element around tool invocation and [MemoryStore] reads it
 * out of `coroutineContext`.
 *
 * A `CoroutineContext` element rather than a parameter on [Tool.invoke] for three reasons:
 * none of the other fifteen tools want it; a [Skill] must not be able to influence
 * provenance, and it cannot influence something it is never handed; and — the one
 * that matters — **absence fails closed**. A call path with no [AgentTurn] installed gets
 * `null` evidence, which [MemoryPolicy] classifies as inferred-and-redacted, and gets no
 * write allowance at all. Forgetting to install it makes memory writes stop, not leak.
 * That is the same property [ShellGate.modify] has by having no signature that could
 * execute.
 *
 * It carries no accessor for the user's raw text; see [UserTurnEvidence].
 */
class AgentTurn(
    val evidence: UserTurnEvidence,
    private val writeBudget: Int = MAX_MEMORY_WRITES_PER_TURN
) : AbstractCoroutineContextElement(AgentTurn) {

    companion object Key : CoroutineContext.Key<AgentTurn> {
        /**
         * How many memories one turn may write.
         *
         * An injection that lands one memory is a nuisance; one that lands two hundred has
         * rewritten the assistant, and the difference should be structural rather than a
         * question of how persuasive the notification was. Five is above anything a real
         * turn does — the model is answering a question, not importing a contact list —
         * and the loop caps at five tool iterations anyway, so a turn cannot spend long
         * trying.
         */
        const val MAX_MEMORY_WRITES_PER_TURN = 5
    }

    private val writes = AtomicInteger(0)

    /**
     * Claims one of this turn's memory writes, or returns false once they are spent.
     *
     * Counts attempts rather than successes: a turn that tried six times has been trying
     * to write six memories whether or not the store liked them, and that is the number
     * the cap is about.
     */
    fun tryClaimMemoryWrite(): Boolean = writes.incrementAndGet() <= writeBudget

    /** How many writes have been attempted, for the refusal message and for tests. */
    val attemptedWrites: Int get() = writes.get()
}
