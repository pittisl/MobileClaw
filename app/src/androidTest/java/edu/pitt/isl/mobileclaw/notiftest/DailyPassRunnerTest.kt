package edu.pitt.isl.mobileclaw.notiftest

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [DailyPassRunner]'s four refusals — the guards that decide whether the one scheduled LLM
 * call in the app happens at all.
 *
 * The arrangement is what makes these assertions mean something: the runner is built with a
 * **dead endpoint**. Any guard that failed to stop would try to reach it and come back
 * `Failed`, so `Paused`, `AlreadySummarised` and `NothingToDo` are each proof that no
 * request was attempted — not merely that the return value looked right.
 *
 * Instrumented because every guard reads something only a device has: real
 * `SharedPreferences` for the pause flag and the real table for the other two.
 */
@RunWith(AndroidJUnit4::class)
class DailyPassRunnerTest {

    private lateinit var context: Context

    /** Nothing listens on port 1; reaching the network at all is a test failure. */
    private fun runner() = DailyPassRunner(
        context,
        endpoint = "http://127.0.0.1:1/v1/chat/completions",
        apiKey = "test-key-not-used",
        model = "test-model"
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AgentRunState.init(context)
        AgentRunState.setPaused(false)
        MemoryStore.init(context)
        MemoryStore.clear()
        awaitUntil(message = "store did not clear") { MemoryStore.memories.value.isEmpty() }
    }

    @After
    fun tearDown() {
        AgentRunState.setPaused(false)
        MemoryStore.clear()
    }

    /**
     * The pause guard: this is the one LLM call no
     * notification and no user action triggers, so nothing else in the app would stop it.
     * A paused agent that still phoned home at 3am would make the pause a lie.
     */
    @Test
    fun aPausedAgentDoesNotPhoneHome() = runTest {
        AgentRunState.setPaused(true)

        assertEquals(
            DailyPassRunner.Outcome.Paused,
            runner().run(DailyPass.previousDayKey(System.currentTimeMillis()))
        )
    }

    /**
     * Idempotence, asked of the data rather than of a flag. A periodic job is retried and
     * re-enqueued on a schedule the app does not fully control, and two summaries for one
     * day are not cosmetic: both are stored, they disagree, and the second one cost a call.
     */
    @Test
    fun aDayIsSummarisedOnlyOnce() = runTest {
        val dayKey = "2001-01-02"
        val now = System.currentTimeMillis()
        MobileClawDb.get(context).memories().insert(
            MemoryEntity(
                content = "A quiet day.",
                provenance = MemoryProvenance.DAILY_SUMMARY.name,
                redacted = false,
                createdMs = now,
                updatedMs = now,
                dayKey = dayKey,
                supersededBy = null,
                pinned = false
            )
        )
        awaitUntil(message = "summary never surfaced") { MemoryStore.memories.value.isNotEmpty() }

        assertEquals(DailyPassRunner.Outcome.AlreadySummarised, runner().run(dayKey))
    }

    /**
     * "One bounded call a day" has to include not making it. A day with no activity and
     * nothing to merge has nothing to say, and the summary it would produce is a token cost
     * with no reader.
     */
    @Test
    fun aDayWithNothingInItIsNotWorthACall() = runTest {
        // Far enough back that no log row, decision or memory falls inside it.
        assertEquals(DailyPassRunner.Outcome.NothingToDo, runner().run("2001-01-01"))
    }

    /** A blank key is checked before anything else is read, as it is in triage. */
    @Test
    fun noKeyMeansNoCall() = runTest {
        val unconfigured = DailyPassRunner(
            context,
            endpoint = "http://127.0.0.1:1/v1/chat/completions",
            apiKey = "",
            model = "test-model"
        )
        assertEquals(DailyPassRunner.Outcome.NotConfigured, unconfigured.run("2001-01-01"))
    }
}
