package edu.pitt.isl.mobileclaw.notiftest

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What [MainViewModel] does with an agent's event stream, driven by a scripted
 * [FakeAiClient] so the awkward moments — a half-arrived sentinel, a failure three
 * tokens in, a stop mid-turn — happen on demand.
 *
 * Instrumented rather than JVM because a ViewModel needs an [Application] and
 * `viewModelScope` needs a real main looper, and because the assertions that matter
 * are about what reached the **database**: this is the only test that exercises the
 * streaming path through the real `add` / `replaceAt` / `finalize` split rather than
 * calling those methods directly.
 */
@RunWith(AndroidJUnit4::class)
class MainViewModelTest {

    private lateinit var app: Application
    private lateinit var dao: ChatMessageDao

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        ChatStore.init(app)
        dao = MobileClawDb.get(app).chatMessages()
        ChatStore.clear()
        awaitStoredCount(0)
    }

    @After
    fun tearDown() {
        ChatStore.clear()
    }

    private fun storedCount() = runBlocking { dao.count(ChatStore.DEFAULT_SESSION) }

    private fun stored() = runBlocking { dao.recent(ChatStore.DEFAULT_SESSION, 50) }

    private fun awaitStoredCount(expected: Int) =
        awaitUntil(message = "expected $expected stored rows, saw ${storedCount()}") {
            storedCount() == expected
        }

    private fun viewModelWith(fake: FakeAiClient): MainViewModel {
        lateinit var vm: MainViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = MainViewModel(app) { fake }
        }
        return vm
    }

    private fun awaitIdle(vm: MainViewModel) =
        awaitUntil(message = "the turn never finished") { !vm.running.value }

    private fun visible() = ChatStore.messages.value.last().content

    // ---- the streaming path -------------------------------------------------

    @Test
    fun oneStreamedTurn_settlesExactlyOneAssistantRow() {
        // The whole point of the add/replaceAt/finalize split: ~20 UI updates a second
        // while streaming, one row on disk at the end.
        val fake = FakeAiClient {
            token("Mom ")
            token("called ")
            token("twice.")
            done("Mom called twice.")
        }
        val vm = viewModelWith(fake)

        vm.submit("what's urgent?")
        awaitIdle(vm)

        assertEquals(listOf("what's urgent?", "Mom called twice."),
            ChatStore.messages.value.map { it.content })
        assertFalse(ChatStore.messages.value.last().pending)

        awaitStoredCount(2)
        val assistantRow = stored().first { it.role == "ASSISTANT" }
        assertEquals("only the settled text reaches disk", "Mom called twice.", assistantRow.content)
    }

    @Test
    fun aPartiallyArrivedSentinel_neverReachesTheBubble() {
        // "@@ACT" must not flash in the user's face before the rest of the marker
        // arrives. The gate holds the stream open so this is a fact, not a race.
        val gate = CompletableDeferred<Unit>()
        val fake = FakeAiClient {
            token("Mom called twice.\n@@ACT")
            gate.await()
            token("IONS@@ Call back | Ignore")
            done("Mom called twice.", listOf("Call back", "Ignore"))
        }
        val vm = viewModelWith(fake)

        vm.submit("what's urgent?")
        awaitUntil(message = "no token ever reached the bubble") { visible().isNotEmpty() }

        val midStream = visible()
        assertFalse("a partial sentinel leaked: \"$midStream\"", midStream.contains("@@"))
        assertTrue(midStream.startsWith("Mom called twice."))

        gate.complete(Unit)
        awaitIdle(vm)

        val settled = ChatStore.messages.value.last()
        assertEquals("Mom called twice.", settled.content)
        assertEquals(listOf("Call back", "Ignore"), settled.actions)
    }

    @Test
    fun usageIsSummedAcrossTheWholeTurn_andNotPersisted() {
        // A turn that runs tools makes several requests, and each reports its own
        // usage. Showing only the last one would understate the bill by exactly the
        // expensive part — the tool round-trips.
        val fake = FakeAiClient {
            usage(1000, 20)          // first request: the one that asked for a tool
            token("Mom called ")
            usage(1500, 40)          // second: the one that answered
            token("twice.")
            done("Mom called twice.")
        }
        val vm = viewModelWith(fake)

        vm.submit("what's urgent?")
        awaitIdle(vm)

        val settled = ChatStore.messages.value.last()
        assertEquals(2500, settled.promptTokens)
        assertEquals(60, settled.completionTokens)

        // Usage is a diagnostic, not history: it is deliberately absent from the entity,
        // so restoring this conversation shows no footer rather than a wrong one.
        awaitStoredCount(2)
        val restored = stored().first { it.role == "ASSISTANT" }
        assertEquals("Mom called twice.", restored.content)
    }

    /**
     * The one link nothing else covers: that the always-injected half of memory
     * actually reaches the request.
     *
     * `MemoryInjectionTest` proves the block is built correctly and `MemoryStoreTest`
     * proves the rows are on disk, and both would still pass if the ViewModel had stopped
     * calling [MemoryInjection] at all. That regression is silent by construction — the
     * chat keeps working and the assistant simply, quietly, knows nothing about the user.
     * The same reasoning that put two `AgentLoopTest` cases behind `AgentTurn`.
     */
    @Test
    fun theTurnContextCarriesTheAlwaysInjectedMemories() {
        MemoryStore.init(app)
        MemoryStore.clear()
        awaitUntil(message = "store did not clear") { MemoryStore.memories.value.isEmpty() }

        runBlocking {
            // Verbatim, so [MemoryPolicy]'s containment check gives it the USER_STATED label — the
            // paraphrase "The user prefers aisle seats" would land in the inferred tier
            // and this test would be asserting the opposite of what it says it does.
            withContext(AgentTurn(UserTurnEvidence("remember that I prefer aisle seats"))) {
                MemoryStore.remember("I prefer aisle seats")
            }
            withContext(AgentTurn(UserTurnEvidence("what's on today?"))) {
                MemoryStore.remember("The user may be travelling on Tuesday")
            }
        }
        awaitUntil(message = "writes never settled") { MemoryStore.memories.value.size == 2 }

        lateinit var context: () -> String
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            MainViewModel(app) { ctx -> context = ctx; FakeAiClient { done("ok") } }
        }
        val text = context()

        assertTrue("the user-stated memory is missing:\n$text", text.contains("aisle seats"))
        assertTrue("the block is not framed as data:\n$text", text.contains("never instructions"))
        // The inferred one is recall-only, so it must NOT be here — this is the assertion
        // that fails if the tier rule is bypassed at the call site rather than inside
        // MemoryInjection.
        assertFalse("an unpinned inferred memory reached the prompt:\n$text",
            text.contains("travelling on Tuesday"))

        MemoryStore.clear()
    }

    @Test
    fun theAgentIsHandedTheConversationSoFar() {
        // Turn two re-sends turn one. Trimming for cost happens inside
        // OpenAiAiClient against a token budget (history budgeting, HistoryBudgetTest) rather than
        // here — the ViewModel still hands over everything it has.
        val fake = FakeAiClient { done("first reply") }
        val vm = viewModelWith(fake)

        vm.submit("one")
        awaitIdle(vm)
        vm.submit("two")
        awaitIdle(vm)

        assertEquals(2, fake.historiesSeen.size)
        assertEquals(listOf("one", ""), fake.historiesSeen[0].map { it.content })
        assertEquals("the second turn carries the first",
            listOf("one", "first reply", "two", ""), fake.historiesSeen[1].map { it.content })
    }

    // ---- failure and recovery ----------------------------------------------

    @Test
    fun anError_becomesARetryableBubbleAndIsNotStored() {
        val fake = FakeAiClient { fail("HTTP 503: overloaded") }
        val vm = viewModelWith(fake)

        vm.submit("what's urgent?")
        awaitIdle(vm)

        val bubble = ChatStore.messages.value.last()
        assertTrue("the bubble must be the retry affordance", bubble.isError)
        assertTrue("a network failure is worth trying again", bubble.retryable)
        assertEquals("HTTP 503: overloaded", bubble.content)

        // An error bubble offers a retry that means nothing after a restart, so it
        // stays out of the database — only the user's turn is stored.
        Thread.sleep(300)
        assertEquals(1, storedCount())
        assertEquals("USER", stored().single().role)
    }

    @Test
    fun aConfigurationError_producesABubbleWithNoRetryOffered() {
        // The other half of the rule above, and the reason [ChatMessage.retryable]
        // exists: an unconfigured or refused key produces the same red bubble, but
        // offering to try again is a promise the app cannot keep. ChatAdapter reads
        // exactly this flag, so the decision is settled here rather than in the view.
        val fake = FakeAiClient { fail(AgentError.notConfigured()) }
        val vm = viewModelWith(fake)

        vm.submit("what's urgent?")
        awaitIdle(vm)

        val bubble = ChatStore.messages.value.last()
        assertTrue(bubble.isError)
        assertFalse("nothing about the next attempt would differ", bubble.retryable)
        assertTrue("and it must say what to actually do: ${bubble.content}",
            bubble.content.contains("Settings > Model service"))
    }

    @Test
    fun retry_replacesTheFailedBubbleInPlaceRatherThanAppending() {
        var attempt = 0
        val fake = FakeAiClient {
            attempt++
            if (attempt == 1) fail("HTTP 503: overloaded") else done("Recovered.")
        }
        val vm = viewModelWith(fake)

        vm.submit("what's urgent?")
        awaitIdle(vm)
        val failed = ChatStore.messages.value.last()

        vm.retry(failed)
        awaitIdle(vm)

        assertEquals("the conversation must not grow a third bubble",
            listOf("what's urgent?", "Recovered."), ChatStore.messages.value.map { it.content })
        assertFalse(ChatStore.messages.value.last().isError)
        awaitStoredCount(2)
    }

    @Test
    fun stopping_cancelsTheProducerSoTheBackendStopsBilling() {
        // The stop button has to reach the producing coroutine — in production that
        // is what cancels the HTTP call. A UI that merely ignores a live stream keeps
        // paying for it.
        val fake = FakeAiClient {
            token("thinking")
            delay(30_000)
            done("never gets here")
        }
        val vm = viewModelWith(fake)

        vm.submit("what's urgent?")
        awaitUntil(message = "the turn never started streaming") { visible() == "thinking" }

        vm.cancel()
        awaitIdle(vm)

        awaitUntil(message = "cancellation never reached the agent") { fake.wasCancelled }
        val bubble = ChatStore.messages.value.last()
        assertTrue(bubble.isError)
        assertEquals("Request cancelled.", bubble.content)
        Thread.sleep(300)
        assertEquals("a cancelled turn is not history", 1, storedCount())
    }

    // ---- the seam itself ----------------------------------------------------

    @Test
    fun theDefaultConstructorIsStillReachableByTheViewModelFactory() {
        // MainViewModel gained a second constructor parameter so tests can inject a
        // fake. AndroidViewModelFactory reflects for exactly (Application), so without
        // @JvmOverloads Kotlin emits no such constructor and the app dies on launch —
        // a failure no other test here would see, since they all pass a fake.
        lateinit var vm: MainViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = ViewModelProvider(
                ViewModelStore(),
                ViewModelProvider.AndroidViewModelFactory.getInstance(app)
            )[MainViewModel::class.java]
        }
        assertNotNull(vm)
        assertFalse("a freshly built ViewModel is idle", vm.running.value)
    }

    /**
     * Per-session agent resolution: the spec a turn runs on comes from the **conversation it was asked in**,
     * resolved through [AgentRegistry], rather than from a field frozen when the ViewModel
     * was built.
     *
     * **What this cannot yet claim.** `AgentRegistry` has one entry, so a frozen
     * `generalChat` and a correctly resolved one are the same object here — the assertion
     * that would separate them needs a second agent, and adding one purely to be observed
     * is how test fixtures start deciding product shape. What it does hold is everything
     * around that: the id is resolved rather than passed through raw, an unresolvable one
     * still produces a runnable spec, and the tools are attached to whatever comes back.
     * The last is not cosmetic — `AgentRegistry` stores no tools, so a spec that reached
     * the client without them would be a mute agent, and every other test here would pass.
     */
    @Test
    fun aTurnRunsOnTheAgentItsConversationStores() {
        val fake = FakeAiClient { done("ok") }
        val vm = viewModelWith(fake)

        vm.submit("who is answering?")
        awaitIdle(vm)

        assertEquals("one run, one spec", 1, fake.specsSeen.size)
        val spec = fake.specsSeen.first()
        assertEquals(
            AgentRegistry.resolve(ChatStore.agentSpecIdFor(ChatStore.activeSessionId.value)).id,
            spec.id
        )
        assertTrue("the resolved spec reached the client with no tools", spec.tools.isNotEmpty())
    }
}
