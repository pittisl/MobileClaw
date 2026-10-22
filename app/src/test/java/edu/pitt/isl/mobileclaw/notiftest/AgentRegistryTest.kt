package edu.pitt.isl.mobileclaw.notiftest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How a stored agent id becomes an agent.
 *
 * This is the whole of the read side of per-session agents, and it is JVM-testable because
 * [AgentRegistry] has no Android dependency beyond a log line. The cases that matter are
 * the ones where the id does *not* name an agent: a session keeps its agent's id on disk
 * for as long as the conversation exists, so every rename, removal and downgrade turns
 * some rows into references to nothing.
 */
class AgentRegistryTest {

    @Test
    fun theDefaultAgentIsFoundByItsOwnId() {
        val found = AgentRegistry.byId(AgentRegistry.generalChat.id)
        assertNotNull(found)
        assertEquals(AgentRegistry.generalChat.id, found!!.id)
    }

    @Test
    fun anUnknownIdIsNotFound() {
        assertNull(AgentRegistry.byId("no-such-agent"))
        assertNull(AgentRegistry.byId(null))
    }

    /**
     * The legacy stored id `"default"` does not name an agent. It must resolve to the
     * default agent without migration or backfill, preserving access to the session.
     */
    @Test
    fun aSessionWrittenBeforeAgentsExistedRunsOnTheDefaultAgent() {
        assertEquals(
            AgentRegistry.generalChat.id,
            AgentRegistry.resolve(ChatStore.LEGACY_AGENT_SPEC).id
        )
    }

    /**
     * The three ways an id stops resolving — removed, renamed, or never stored at all —
     * all end at the default agent rather than at an exception. The conversation is intact
     * in every one of those cases, so refusing to open it would destroy something real in
     * order to protect a preference.
     */
    @Test
    fun anythingThatDoesNotResolveFallsBackToTheDefaultAgent() {
        assertEquals(AgentRegistry.generalChat.id, AgentRegistry.resolve("removed-agent").id)
        assertEquals(AgentRegistry.generalChat.id, AgentRegistry.resolve("").id)
        assertEquals(AgentRegistry.generalChat.id, AgentRegistry.resolve(null).id)
    }

    /**
     * Resolution is not silently the default for everything — the fallback has to be a
     * fallback. With one agent registered this looks tautological; it is the case that
     * starts failing first if `resolve` is ever simplified into `generalChat`.
     */
    @Test
    fun aKnownIdResolvesToThatAgentRatherThanToTheFallback() {
        AgentRegistry.all().forEach { spec ->
            assertEquals(
                "resolve(${spec.id}) must return that agent",
                spec.id,
                AgentRegistry.resolve(spec.id).id
            )
        }
    }

    /**
     * [AgentRegistry.generalChat] is a `get()`, so each access rebuilds it — that is what
     * stops [LlmConfig]'s model being frozen at class initialisation. Equal by
     * value, never the same instance; a `val` here would pass the first assertion and fail
     * this one.
     */
    @Test
    fun theDefaultAgentIsRebuiltPerAccessRatherThanCached() {
        val first = AgentRegistry.generalChat
        val second = AgentRegistry.generalChat
        assertEquals(first, second)
        if (first === second) {
            throw AssertionError("generalChat is cached — a model changed at runtime would never reach the agent")
        }
    }
}
