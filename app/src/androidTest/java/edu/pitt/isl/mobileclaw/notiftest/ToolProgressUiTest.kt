package edu.pitt.isl.mobileclaw.notiftest

import android.app.Application
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

/** Real ViewModel/store coverage, isolated from the phone's existing conversations. */
@RunWith(AndroidJUnit4::class)
class ToolProgressUiTest {
    private lateinit var app: Application
    private lateinit var dao: ChatMessageDao
    private lateinit var originalSession: String
    private lateinit var testSession: String
    private val viewModels = mutableListOf<MainViewModel>()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        ChatStore.init(app)
        dao = MobileClawDb.get(app).chatMessages()
        originalSession = ChatStore.activeSessionId.value
        testSession = ChatStore.newSession()
        // The queued upsert is also a barrier for ChatStore's startup restoration.
        awaitUntil(message = "temporary conversation was not created") {
            runBlocking { MobileClawDb.get(app).chatSessions().find(testSession) } != null &&
                ChatStore.sessions.value.any { it.id == testSession } &&
                ChatStore.sessions.value.size > 1
        }
        ChatStore.switchTo(testSession)
        ChatStore.clear(testSession)
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.cancel() }
        viewModels.forEach { awaitIdle(it) }
        if (::originalSession.isInitialized) ChatStore.switchTo(originalSession)
        if (::testSession.isInitialized) {
            // deleteSession clears the active chat if only one session exists; never
            // permit that fallback on a user's phone, even if setup failed partway.
            awaitUntil(message = "temporary conversation cannot be safely removed") {
                ChatStore.sessions.value.any { it.id == testSession } &&
                    ChatStore.sessions.value.any { it.id != testSession }
            }
            ChatStore.deleteSession(testSession)
            awaitUntil(message = "temporary conversation was not removed") {
                runBlocking { MobileClawDb.get(app).chatSessions().find(testSession) } == null &&
                    storedCount() == 0
            }
        }
    }

    private fun storedCount() = runBlocking { dao.count(testSession) }
    private fun stored() = runBlocking { dao.recent(testSession, 50) }
    private fun awaitStoredCount(expected: Int) =
        awaitUntil(message = "expected $expected stored rows in the temporary conversation") {
            storedCount() == expected
        }

    private fun viewModelWith(fake: FakeAiClient): MainViewModel {
        lateinit var vm: MainViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = MainViewModel(app) { fake }
        }
        viewModels += vm
        return vm
    }

    private fun awaitIdle(vm: MainViewModel) =
        awaitUntil(message = "the turn never finished") { !vm.running.value }

    private fun visible() = ChatStore.messages.value.last().content

    @Test
    fun clickingAnOlderCalendarChipCarriesItsOwnSuggestion() {
        val source = ChatMessage(ChatMessage.Role.ASSISTANT,
            "Meet at Jim's office at 9pm tonight.", actions = listOf("Add to calendar"))
        val newer = source.copy(content = "Lunch with Amy tomorrow at noon.", timestampMs = source.timestampMs + 1)
        ChatStore.add(source)
        ChatStore.add(newer)
        val fake = FakeAiClient { done("Prepared for confirmation.") }
        val vm = viewModelWith(fake)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val adapter = ChatAdapter(vm::submitAction, {})
            adapter.submitList(listOf(source, newer))
            val parent = FrameLayout(ContextThemeWrapper(app, R.style.Theme_MobileClaw))
            val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0))
            adapter.onBindViewHolder(holder, 0)
            assertTrue(holder.itemView.findViewById<LinearLayout>(R.id.actionButtons)
                .getChildAt(0).performClick())
        }
        awaitIdle(vm)
        val input = fake.historiesSeen.single().last { it.role == ChatMessage.Role.USER }
        assertEquals("Add to calendar", input.content)
        val context = JSONObject(requireNotNull(input.selectedActionContext))
        assertEquals(source.content, context.getString("suggestion"))
        assertFalse(context.getString("suggestion").contains("Amy"))
        awaitStoredCount(4)
        assertEquals("Add to calendar", stored().single { it.role == "USER" }.content)
    }

    @Test
    fun aChipFromAClearedConversationDoesNotStartATurn() {
        val source = ChatMessage(ChatMessage.Role.ASSISTANT, "Meet Jim at 9pm",
            actions = listOf("Add to calendar"))
        ChatStore.add(source)
        awaitStoredCount(1)
        ChatStore.clear(testSession)
        awaitStoredCount(0)
        val fake = FakeAiClient { done("should not run") }
        val vm = viewModelWith(fake)
        vm.submitAction(source, "Add to calendar")
        assertTrue(fake.historiesSeen.isEmpty())
        assertTrue(ChatStore.messages.value.isEmpty())
    }

    @Test
    fun toolProgressStaysOutOfHistoryAndDisk_thenYieldsToTheFirstProse() {
        val finishReads = CompletableDeferred<Unit>()
        val startProse = CompletableDeferred<Unit>()
        val finishTurn = CompletableDeferred<Unit>()
        val fake = FakeAiClient {
            emit(AgentEvent.ToolCall("contact", "lookup_contact", "PRIVATE QUERY"))
            finishReads.await()
            emit(AgentEvent.ToolResult("contact", "PRIVATE PHONE NUMBER"))
            emit(AgentEvent.ToolCall("calendar", "read_calendar", "{}"))
            emit(AgentEvent.ToolResult("calendar", "PRIVATE EVENT"))
            startProse.await()
            token("You have time at three.")
            // A later tool batch cannot overwrite prose already on screen.
            emit(AgentEvent.ToolCall("file", "search_files", "{}"))
            emit(AgentEvent.ToolResult("file", "PRIVATE FILE"))
            finishTurn.await()
            done("You have time at three.")
        }
        val vm = viewModelWith(fake)
        try {
            vm.submit("find a callback time")
            awaitUntil(message = "tool activity never reached the bubble") {
                visible() == "Looking up contact…"
            }
            assertTrue(ChatStore.messages.value.last().pending)
            assertEquals(listOf("find a callback time"),
                HistoryBudget.select(ChatStore.messages.value).messages.map { it.content })
            awaitStoredCount(1)

            finishReads.complete(Unit)
            awaitUntil(message = "completed reads disappeared before the answer") {
                visible() == "Contact lookup finished\nCalendar lookup finished"
            }
            assertTrue(ChatStore.messages.value.last().pending)
            assertFalse(visible().contains("PRIVATE"))
            assertEquals("only the user's question is stored during tool work", 1, storedCount())

            startProse.complete(Unit)
            awaitUntil(message = "first prose did not replace the activity") {
                visible() == "You have time at three."
            }
            assertFalse(ChatStore.messages.value.last().pending)
            finishTurn.complete(Unit)
            awaitIdle(vm)
            awaitStoredCount(2)
            assertEquals("You have time at three.", stored().first { it.role == "ASSISTANT" }.content)
        } finally {
            finishReads.complete(Unit)
            startProse.complete(Unit)
            finishTurn.complete(Unit)
            vm.cancel()
        }
    }

    @Test
    fun toolFailureAndRetryHaveFreshProgress_andKeepConfirmationReceiptUnchanged() {
        val failFirst = CompletableDeferred<Unit>()
        val finishRetry = CompletableDeferred<Unit>()
        var attempt = 0
        val receipt = "Waiting for confirmation. Execution has not been confirmed.\nCreate callback task"
        val fake = FakeAiClient {
            attempt++
            if (attempt == 1) {
                emit(AgentEvent.ToolCall("first", "search_files", "{}"))
                failFirst.await()
                fail("HTTP 503: overloaded")
            } else {
                emit(AgentEvent.ToolCall("retry", "read_calendar", "{}"))
                finishRetry.await()
                done(receipt)
            }
        }
        val vm = viewModelWith(fake)
        try {
            vm.submit("prepare a callback task")
            awaitUntil(message = "first attempt did not show its tool") { visible() == "Searching files…" }
            failFirst.complete(Unit)
            awaitIdle(vm)
            val failed = ChatStore.messages.value.last()
            assertTrue(failed.isError)
            assertEquals("HTTP 503: overloaded", failed.content)

            vm.retry(failed)
            awaitUntil(message = "retry retained old activity") { visible() == "Checking calendar…" }
            assertTrue(ChatStore.messages.value.last().pending)
            finishRetry.complete(Unit)
            awaitIdle(vm)
            assertEquals(receipt, visible())
            assertFalse(ChatStore.messages.value.last().pending)
            awaitStoredCount(2)
            assertEquals(receipt, stored().first { it.role == "ASSISTANT" }.content)
        } finally {
            failFirst.complete(Unit)
            finishRetry.complete(Unit)
            vm.cancel()
        }
    }

    @Test
    fun cancellingToolProgressReplacesItWithTheCancellationMessage() {
        val fake = FakeAiClient {
            emit(AgentEvent.ToolCall("calendar", "read_calendar", "{}"))
            delay(30_000)
        }
        val vm = viewModelWith(fake)
        try {
            vm.submit("check my calendar")
            awaitUntil(message = "tool activity was never displayed") { visible() == "Checking calendar…" }
            vm.cancel()
            awaitIdle(vm)
            assertEquals("Request cancelled.", visible())
            assertTrue(ChatStore.messages.value.last().isError)
            assertFalse(ChatStore.messages.value.last().pending)
            awaitStoredCount(1)
        } finally {
            vm.cancel()
        }
    }

}
