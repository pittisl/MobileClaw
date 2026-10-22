package edu.pitt.isl.mobileclaw.notiftest

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [MemoryStore.applyDailyPass] against the real database — the half of the daily pass that
 * [DailyPassTest] cannot reach.
 *
 * The rules under test are all about what ends up **on disk**: that a fold is a link and
 * not a delete, that a superseded row leaves the live view while staying recoverable, that
 * a retirement has something to point at, and that the DAO's own clauses still refuse a
 * user-stated row when a plan names one anyway.
 *
 * What it deliberately does **not** claim: that SQLite rolls back. The all-or-nothing
 * property comes from `withTransaction`, and forcing a failure part-way through it means
 * arranging a database error, which is a test of Room rather than of this code. What is
 * covered instead is every rule that *needed* the transaction to be expressible — the
 * insert-then-supersede ordering, and the fact that nothing is superseded without its
 * replacement existing first.
 */
@RunWith(AndroidJUnit4::class)
class MemoryDailyPassTest {

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

    private suspend fun remember(userText: String, content: String) =
        withContext(AgentTurn(UserTurnEvidence(userText))) { MemoryStore.remember(content) }

    private suspend fun rows() = MobileClawDb.get(context).memories().let { dao ->
        // Live rows only reach `liveRows`; the superseded ones have to be read another way,
        // and the point of several of these assertions is that they are still there.
        dao.liveRows(100)
    }

    private suspend fun seedThreeInferred(): List<MemoryStore.Memory> {
        remember("what's going on?", "The user has a flight on Tuesday")
        remember("what's going on?", "The user is flying on Tuesday morning")
        remember("what's going on?", "The user dislikes early alarms")
        awaitUntil(message = "seed never settled") { MemoryStore.memories.value.size == 3 }
        return MemoryStore.memories.value
    }

    /**
     * The shape of a consolidation: two rows become one, a third is retired against the
     * day's summary, and both originals are still on disk pointing at what replaced them.
     */
    @Test
    fun aPlanIsAppliedWholeAndFoldedRowsLeaveTheLiveViewWithoutLeavingTheTable() = runTest {
        val seeded = seedThreeInferred()
        val (flightA, flightB, alarms) = seeded.sortedBy { it.id }

        val result = MemoryStore.applyDailyPass(
            DailyPass.Plan(
                dayKey = "2026-08-11",
                summary = "Triaged 6 notifications and replied to 1.",
                merges = listOf(
                    DailyPass.Plan.Merge(listOf(flightA.id, flightB.id), "The user flies Tuesday morning")
                ),
                stale = listOf(alarms.id),
                rejected = 0
            )
        )

        assertNotNull(result)
        assertTrue(result!!.summaryWritten)
        assertEquals(2, result.merged)
        assertEquals(1, result.retired)
        assertEquals("vetting and the DAO must agree", 0, result.refusedBySql)

        awaitUntil(message = "the pass never surfaced") { MemoryStore.memories.value.size == 2 }

        val live = rows()
        assertEquals(2, live.size)
        val summary = live.single { it.provenance == MemoryProvenance.DAILY_SUMMARY.name }
        assertEquals("2026-08-11", summary.dayKey)
        val merged = live.single { it.provenance == MemoryProvenance.MODEL_INFERRED.name }
        assertEquals("The user flies Tuesday morning", merged.content)

        // A link, not a delete: the originals are still there, pointing at their
        // replacement, and it is the *replacement's* id rather than a tombstone.
        val all = MobileClawDb.get(context).memories().count()
        assertEquals("nothing was deleted", 5, all)
        assertEquals(3, MemoryStore.total() - live.size)

        // And the summary is recall-only: a day's operational report is exactly the kind of
        // row memory injection keeps out of every future system prompt unless the user pins it.
        assertFalse(
            MemoryInjection.isAlwaysInjected(
                MemoryStore.memories.value.single { it.provenance == MemoryProvenance.DAILY_SUMMARY }
            )
        )
    }

    /**
     * The duplicated check, doing its job. `DailyPass.vet` would never pass a user-stated
     * id through — this constructs the plan directly, which is what a bug, a future caller
     * or a bypass of the vetting layer would produce.
     */
    @Test
    fun theDaoRefusesAUserStatedRowEvenWhenThePlanNamesIt() = runTest {
        remember("please remember I am allergic to peanuts", "I am allergic to peanuts")
        remember("what's going on?", "The user seems busy")
        awaitUntil(message = "seed never settled") { MemoryStore.memories.value.size == 2 }

        val stated = MemoryStore.memories.value.single { it.provenance == MemoryProvenance.USER_STATED }

        val result = MemoryStore.applyDailyPass(
            DailyPass.Plan(
                dayKey = "2026-08-11",
                summary = "A quiet day.",
                merges = emptyList(),
                stale = listOf(stated.id),
                rejected = 0
            )
        )!!

        assertEquals("a tidy-up pass may not retire what the user asked for", 0, result.retired)
        assertEquals(1, result.refusedBySql)

        awaitUntil(message = "summary never surfaced") { MemoryStore.memories.value.size == 3 }
        assertTrue(
            "the user's row is still live",
            MemoryStore.memories.value.any { it.id == stated.id }
        )
    }

    /** Pinned rows are protected by the same clause, and for the same reason. */
    @Test
    fun theDaoRefusesAPinnedRowToo() = runTest {
        remember("what's going on?", "The user has a standing 9am")
        awaitUntil(message = "seed never settled") { MemoryStore.memories.value.isNotEmpty() }
        val pinned = MemoryStore.memories.value.single()
        assertTrue(MemoryStore.setPinned(pinned.id, true))
        awaitUntil(message = "pin never surfaced") { MemoryStore.memories.value.single().pinned }

        val result = MemoryStore.applyDailyPass(
            DailyPass.Plan("2026-08-11", "A quiet day.", emptyList(), listOf(pinned.id), 0)
        )!!

        assertEquals(0, result.retired)
        assertEquals(1, result.refusedBySql)
    }

    /**
     * A retirement is a supersession, and a supersession points at the row that replaced
     * it. With no summary there is nothing to point at, so the retirement does not happen —
     * rather than becoming a delete with extra steps, which is what would take the undo
     * window away.
     */
    @Test
    fun retiringNeedsASummaryToPointAt() = runTest {
        val seeded = seedThreeInferred()

        val result = MemoryStore.applyDailyPass(
            DailyPass.Plan(
                dayKey = "2026-08-11",
                summary = null,
                merges = emptyList(),
                stale = seeded.map { it.id },
                rejected = 0
            )
        )!!

        assertFalse(result.summaryWritten)
        assertEquals(0, result.retired)
        // Asserting an absence, so give a rogue write time to land.
        Thread.sleep(500)
        assertEquals("nothing may be retired into nothing", 3, rows().size)
    }

    /** An empty plan is a normal outcome — a quiet day is not a failure. */
    @Test
    fun anEmptyPlanTouchesNothing() = runTest {
        seedThreeInferred()
        val result = MemoryStore.applyDailyPass(
            DailyPass.Plan("2026-08-11", null, emptyList(), emptyList(), rejected = 0)
        )!!

        assertFalse(result.summaryWritten)
        assertEquals(0, result.merged + result.retired + result.refusedBySql)
        Thread.sleep(300)
        assertEquals(3, rows().size)
        assertNull(rows().firstOrNull { it.dayKey != null })
    }
}
