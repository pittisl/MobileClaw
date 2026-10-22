package edu.pitt.isl.mobileclaw.notiftest

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [MemoryStore] against the real database, for the same reason [ActionLogStoreTest] runs
 * that way: the store is a singleton whose `init` is idempotent, so there is no test DAO
 * to inject.
 *
 * [MemoryPolicyTest] already covers the classification rules on the JVM. What can only be
 * checked here is the half that involves Room and the coroutine context — that the
 * classified content is what actually reaches **disk**, that a write with no [AgentTurn]
 * in scope writes nothing at all, and that the per-turn cap is spent on the turn rather
 * than on the process.
 */
@RunWith(AndroidJUnit4::class)
class MemoryStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        MemoryStore.init(context)
        MemoryStore.clear()
        awaitUntil(message = "store did not clear") { MemoryStore.memories.value.isEmpty() }
    }

    @After
    fun tearDown() {
        MemoryStore.clear()
    }

    private suspend fun rememberIn(
        userText: String,
        content: String,
        turn: AgentTurn = AgentTurn(UserTurnEvidence(userText))
    ) = withContext(turn) { MemoryStore.remember(content) }

    private suspend fun rows() = MobileClawDb.get(context).memories().liveRows(100)

    /**
     * The one that matters most. the user-attested redaction exception means this table can hold unredacted
     * sensitive text, so what has to be checked is that the *policy's* output is what
     * lands — asserted against the database rather than the flow, because the flow could
     * agree with the policy while the insert wrote something else.
     */
    @Test
    fun userTypedSecretsSurviveToDisk_andForeignOnesDoNot() = runTest {
        rememberIn(
            userText = "remember that my advisor Priya is on 412-555-0100",
            content = "Priya (the user's advisor) can be reached at (412) 555-0100"
        )
        awaitUntil(message = "write never surfaced") { MemoryStore.memories.value.isNotEmpty() }

        val kept = rows().single()
        assertTrue("user-typed number should survive", kept.content.contains("555-0100"))
        assertFalse(kept.redacted)

        MemoryStore.clear()
        awaitUntil { MemoryStore.memories.value.isEmpty() }

        // Same shape of fact, but the number never appeared in the user's message — the
        // the memory trust policy chain, where notification text reaches the model and comes back as a
        // memory. It must land redacted even though the tool call looked identical.
        rememberIn(
            userText = "anything I should know?",
            content = "The user's backup code is 998877"
        )
        awaitUntil(message = "second write never surfaced") { MemoryStore.memories.value.isNotEmpty() }

        val scrubbed = rows().single()
        assertFalse("foreign secret must not reach disk", scrubbed.content.contains("998877"))
        assertTrue(scrubbed.redacted)
        assertEquals(MemoryProvenance.MODEL_INFERRED.name, scrubbed.provenance)
    }

    /**
     * Fail-closed, asserted at the store rather than only at the policy: with no
     * [AgentTurn] the call must write *nothing*, not write something inferred. A caller
     * reaching the tool loop with no user message behind it is precisely the one that
     * should not be able to teach the assistant anything.
     */
    @Test
    fun noTurnInScope_writesNothingAtAll() = runTest {
        val outcome = MemoryStore.remember("The user likes oat milk")
        assertEquals(MemoryStore.WriteOutcome.NoTurnInScope, outcome)
        // Asserting an absence, so give a rogue insert time to land before looking.
        Thread.sleep(500)
        assertTrue("nothing may be written without a turn", rows().isEmpty())
    }

    /** the memory trust policy's per-turn cap: one injection landing five memories is not two hundred. */
    @Test
    fun oneTurnCannotWriteUnbounded() = runTest {
        val turn = AgentTurn(UserTurnEvidence("remember all of this please"))
        val outcomes = (1..AgentTurn.MAX_MEMORY_WRITES_PER_TURN + 3).map { i ->
            rememberIn("remember all of this please", "Fact number $i", turn)
        }

        assertTrue(
            outcomes.take(AgentTurn.MAX_MEMORY_WRITES_PER_TURN)
                .all { it is MemoryStore.WriteOutcome.Stored }
        )
        assertTrue(
            outcomes.drop(AgentTurn.MAX_MEMORY_WRITES_PER_TURN)
                .all { it == MemoryStore.WriteOutcome.TurnCapReached }
        )

        awaitUntil(message = "writes never settled") {
            MemoryStore.memories.value.size == AgentTurn.MAX_MEMORY_WRITES_PER_TURN
        }

        // The cap belongs to the turn, not to the process — the next turn starts fresh,
        // or a capped conversation would be permanently unable to remember anything.
        val next = rememberIn("and remember this too", "A fact from the next turn")
        assertTrue(next is MemoryStore.WriteOutcome.Stored)
    }

    /**
     * Pinning is what moves a row into the always-injected half, and it is the one
     * input to that decision the user drives. Checked against the database rather than the
     * flow for the same reason as everything else here: the flow could be re-emitting a
     * value the `UPDATE` never actually produced.
     */
    @Test
    fun pinningReachesDisk_andIsWhatPutsAnInferredRowInThePrompt() = runTest {
        rememberIn(
            userText = "what's going on today?",
            content = "The user has a flight on Tuesday"
        )
        awaitUntil(message = "write never surfaced") { MemoryStore.memories.value.isNotEmpty() }

        val inferred = rows().single()
        assertEquals(MemoryProvenance.MODEL_INFERRED.name, inferred.provenance)
        assertFalse(inferred.pinned)
        // Recall-only until pinned — the tier rule, at the row that actually exists.
        assertFalse(MemoryInjection.isAlwaysInjected(MemoryStore.memories.value.single()))

        assertTrue(MemoryStore.setPinned(inferred.id, true))
        awaitUntil(message = "pin never surfaced") { MemoryStore.memories.value.single().pinned }
        assertTrue(rows().single().pinned)
        assertTrue(MemoryInjection.isAlwaysInjected(MemoryStore.memories.value.single()))

        assertTrue(MemoryStore.setPinned(inferred.id, false))
        awaitUntil(message = "unpin never surfaced") { !MemoryStore.memories.value.single().pinned }
        assertFalse("pinning a row that no longer exists is not a success",
            MemoryStore.setPinned(inferred.id + 9_999, true))
    }

    /** `recall` is what memory injection's on-demand half runs on, and what `forget` gets ids from. */
    @Test
    fun recallMatchesTerms_andForgetRemovesOneRow() = runTest {
        rememberIn("remember I am allergic to peanuts", "The user is allergic to peanuts")
        rememberIn("remember I prefer aisle seats", "The user prefers aisle seats")
        awaitUntil(message = "writes never settled") { MemoryStore.memories.value.size == 2 }

        val hits = MemoryStore.recall("peanuts")
        assertEquals(1, hits.size)
        assertTrue(hits.single().content.contains("peanuts"))

        // Tokens of 2 characters or fewer are dropped, and a query left with no usable
        // terms lists the recent rows rather than matching nothing — "what do you know
        // about me" should not come back empty because the model passed a preposition.
        assertEquals(2, MemoryStore.recall("of").size)
        assertEquals(0, MemoryStore.recall("skiing").size)

        assertTrue(MemoryStore.forget(hits.single().id))
        awaitUntil(message = "delete never surfaced") { MemoryStore.memories.value.size == 1 }
        assertFalse("deleting a gone row is a normal answer", MemoryStore.forget(hits.single().id))
    }

    /**
     * The rendering the memory trust policy requires: provenance on every line, so an inferred memory
     * derived from a notification cannot read like something the user said.
     */
    @Test
    fun renderingAlwaysCarriesProvenance() = runTest {
        // The first keeps the user's own wording, so it earns the trusted tier; the second
        // is the model's own observation and cannot. This pair is also what catches the
        // `remember` tool description drifting back towards demanding third person, which
        // would make USER_STATED unreachable and the tier decorative.
        rememberIn("please remember I am allergic to peanuts", "I am allergic to peanuts")
        rememberIn("what's up?", "The user seems busy this week")
        awaitUntil(message = "writes never settled") { MemoryStore.memories.value.size == 2 }

        val rendered = MemoryStore.recall(null).map { it.renderForModel() }
        assertTrue(rendered.any { it.contains("stated by the user") })
        assertTrue(rendered.any { it.contains("inferred, unverified") })
        assertTrue(
            "every line must name an id for forget to use",
            rendered.all { it.startsWith("[#") }
        )
    }
}
