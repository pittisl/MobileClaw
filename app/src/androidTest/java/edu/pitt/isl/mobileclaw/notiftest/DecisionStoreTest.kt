package edu.pitt.isl.mobileclaw.notiftest

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises [DecisionStore] the way [ReasoningModule] does, against the real on-disk
 * database — same reasoning as [ActionLogStoreTest]: the store is a singleton whose
 * `init` is idempotent, so no test DAO can be injected, and each test clears before
 * and after so nothing leaks between them or into a real run.
 */
@RunWith(AndroidJUnit4::class)
class DecisionStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DecisionStore.init(context) // idempotent; MobileClawApp already did this
        DecisionStore.clear()
        awaitUntil(message = "store did not clear") { DecisionStore.decisions.value.isEmpty() }
    }

    @After
    fun tearDown() {
        DecisionStore.clear()
    }

    private fun decision(
        key: String = "0|com.whatsapp|1",
        action: ActionDecision.Action = ActionDecision.Action.REPLY,
        priority: ActionDecision.Priority = ActionDecision.Priority.HIGH,
        reasoning: String = "Direct question from a contact.",
        replyText: String = "Sure, see you at 2.",
        confidence: Float = 0.94f,
        snooze: Int? = null,
        actionTitle: String = ""
    ) = ActionDecision(key, action, priority, reasoning, replyText, confidence, snooze, actionTitle)

    private fun addOne(d: ActionDecision = decision()) {
        val before = DecisionStore.decisions.value.size
        DecisionStore.add(d)
        awaitUntil(message = "add() never surfaced in decisions") {
            DecisionStore.decisions.value.size > before
        }
    }

    private suspend fun newestRow() =
        MobileClawDb.get(context).actionDecisions().observeRecent(1).first().single()

    /**
     * The important one, and the reason this store gained a redaction step it never
     * needed while it was in memory: the row is what [DataExport] hands to the user, so
     * a secret written here outlives the notification and leaves the device.
     *
     * `reasoning` is the field that matters most. The model's *input* is already
     * redacted, but [ActionDecision.fallback] puts a raw exception message here, and
     * that text never went through the prompt at all.
     */
    @Test
    fun add_redactsBeforeItReachesDisk() = runTest {
        addOne(
            decision(
                reasoning = "Message contained the code 481529 so it looks like an OTP",
                replyText = "Your code is 481529"
            )
        )

        val stored = newestRow()

        assertFalse("the OTP must not be stored: ${stored.reasoning}",
            stored.reasoning.contains("481529"))
        assertFalse("nor in the draft reply: ${stored.replyText}",
            stored.replyText.contains("481529"))
        assertTrue(stored.reasoning.contains("[REDACTED]"))
    }

    @Test
    fun add_redactsTheFallbackPathThatNeverSawThePrompt() = runTest {
        // fallback() is the one route where reasoning is not model output at all — it
        // carries an exception message, which can quote whatever the failure touched.
        addOne(ActionDecision.fallback("0|Gmail|9", "parse failed near 4815-1623-9012-3456"))

        val stored = newestRow()

        assertFalse("a card number in an error message is still a card number: ${stored.reasoning}",
            stored.reasoning.contains("4815"))
    }

    /** Everything that isn't sensitive has to survive the round trip intact. */
    @Test
    fun add_thenRestore_preservesEveryField() = runTest {
        val original = decision(
            key = "0|com.todoist|7",
            action = ActionDecision.Action.SNOOZE,
            priority = ActionDecision.Priority.MEDIUM,
            reasoning = "Task due later today.",
            replyText = "",
            confidence = 0.87f,
            snooze = 90,
            actionTitle = "Mark as read"
        )
        addOne(original)

        val restored = DecisionStore.decisions.value.single()

        assertEquals(original, restored)
    }

    /**
     * Enums are stored by name and read back through `fromString`, so a row written by
     * a build with a constant this one doesn't know degrades to the documented default
     * instead of throwing inside the listener.
     */
    @Test
    fun anUnknownEnumNameDegradesInsteadOfCrashing() = runTest {
        val dao = MobileClawDb.get(context).actionDecisions()
        dao.insert(
            ActionDecisionEntity(
                timeMs = System.currentTimeMillis(),
                notificationKey = "0|future|1",
                action = "TELEPORT",       // a value no build of this app has
                priority = "CRITICAL",
                reasoning = "from a newer build",
                replyText = "",
                confidence = 0.5f,
                snoozeDurationMinutes = null,
                actionTitle = ""
            )
        )
        awaitUntil(message = "the inserted row never reached the flow") {
            DecisionStore.decisions.value.isNotEmpty()
        }

        val restored = DecisionStore.decisions.value.single()
        assertEquals(ActionDecision.Action.DO_NOTHING, restored.action)
        assertEquals(ActionDecision.Priority.LOW, restored.priority)
    }

    /** Room re-runs the DAO query on every write, so the flow tracks the table. */
    @Test
    fun decisions_reflectWritesAndAreOldestFirst() {
        addOne(decision(key = "first"))
        addOne(decision(key = "second"))

        assertEquals(
            listOf("first", "second"),
            DecisionStore.decisions.value.map { it.notificationKey }
        )
    }

    /**
     * The continuity this store exists for: [ReasoningModule] feeds the newest few
     * decisions back into its prompt, and before persistence that context reset every
     * time the process died.
     */
    @Test
    fun decisionsSurviveAFreshReadOfTheDatabase() = runTest {
        addOne(decision(key = "0|com.slack|3", reasoning = "Already handled this thread."))

        val fromDisk = MobileClawDb.get(context).actionDecisions().observeRecent(200).first()

        assertEquals(1, fromDisk.size)
        assertEquals("0|com.slack|3", fromDisk.single().notificationKey)
    }

    @Test
    fun clear_emptiesTheTableNotJustMemory() = runTest {
        addOne()

        DecisionStore.clear()
        awaitUntil(message = "clear() did not empty the store") {
            DecisionStore.decisions.value.isEmpty()
        }

        assertEquals(0, MobileClawDb.get(context).actionDecisions().count())
    }
}
