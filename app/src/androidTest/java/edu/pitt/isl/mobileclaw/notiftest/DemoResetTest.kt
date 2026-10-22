package edu.pitt.isl.mobileclaw.notiftest

import android.app.Application
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** No live endpoint or outbound actions: exercise cancellation and the actual menu on a phone. */
@RunWith(AndroidJUnit4::class)
class DemoResetTest {
    private lateinit var app: Application
    private lateinit var session: String
    private lateinit var originalSession: String
    private var priorPaused: Boolean? = null
    private val main = InstrumentationRegistry.getInstrumentation()

    @Before fun prepareIsolatedChat() {
        app = ApplicationProvider.getApplicationContext()
        ChatStore.init(app)
        if (InstrumentationRegistry.getArguments().getString("listenerReset") == "true") {
            priorPaused = AgentRunState.isPaused
            AgentRunState.setPaused(true)
            val component = "${app.packageName}/${NotifListener::class.java.name}"
            main.uiAutomation.executeShellCommand("cmd notification allow_listener $component").close()
            awaitUntil(message = "notification listener did not connect") { NotifListener.instance != null }
        }
        originalSession = ChatStore.activeSessionId.value
        session = ChatStore.newSession()
        awaitUntil { ChatStore.sessions.value.any { it.id == session } }
    }

    @After fun restoreChat() {
        ChatStore.switchTo(originalSession)
        ChatStore.deleteSession(session)
        priorPaused?.let {
            AgentRunState.setPaused(it)
            if (!it) main.runOnMainSync { NotifListener.instance?.onRunStateResumed() }
        }
    }

    private fun storedCount() = runBlocking {
        MobileClawDb.get(app).chatMessages().count(session)
    }

    // Espresso 3.5 reflects InputManager.getInstance, removed on Android 16. Use the
    // platform's supported input injection, with bounds read from the visible UI.
    private fun clickText(label: String) {
        main.waitForIdleSync()
        main.uiAutomation.waitForIdle(250, 5000)
        if (android.os.Build.VERSION.SDK_INT >= 33) main.uiAutomation.clearCache()
        var bounds: Rect? = null
        try { awaitUntil(message = "visible UI label missing: $label") {
            val nodes = main.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label).orEmpty()
            val node = nodes.firstNotNullOfOrNull { n ->
                if (n.text?.toString() != label || !n.isVisibleToUser) return@firstNotNullOfOrNull null
                // A fading dialog's title has the same text as the next menu item.
                // Wait for a clickable item rather than tapping that stale title.
                var target: android.view.accessibility.AccessibilityNodeInfo? = n
                while (target != null && !target.isClickable) target = target.parent
                target?.takeIf { it.isEnabled }
            }
            bounds = node?.let { Rect().also { r -> it.getBoundsInScreen(r) } }
            bounds?.isEmpty == false
        } } catch (error: AssertionError) {
            main.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(app.cacheDir, "demo-reset-ui-failure.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            throw error
        }
        val r = bounds!!
        android.util.Log.d("MobileClaw-TestReset", "Click $label at $r")
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, r.exactCenterX(), r.exactCenterY(), 0)
        val up = MotionEvent.obtain(now, now + 30, MotionEvent.ACTION_UP, r.exactCenterX(), r.exactCenterY(), 0)
        down.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        up.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        try {
            assertTrue(main.uiAutomation.injectInputEvent(down, true))
            assertTrue(main.uiAutomation.injectInputEvent(up, true))
            main.waitForIdleSync()
        } finally { down.recycle(); up.recycle() }
    }

    @Test fun cancellationDrainsBeforeClearAndTheNextTurnHasNoOldHistory() {
        val draining = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val fake = FakeAiClient {
            if (++calls == 1) {
                try {
                    token("Previous visitor")
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        draining.complete(Unit)
                        release.await()
                        // An old producer finishing late is the race the reset must drain.
                        ChatStore.add(ChatMessage(ChatMessage.Role.SYSTEM, "Old turn finished"))
                    }
                }
            } else done("New visitor")
        }
        lateinit var vm: MainViewModel
        main.runOnMainSync { vm = MainViewModel(app) { fake }; vm.submit("Old question") }
        runBlocking { fake.awaitStarted() }
        main.runOnMainSync { vm.resetDemo() }
        try {
            runBlocking { draining.await() }
            assertTrue(vm.resetting.value)
            main.runOnMainSync { vm.submit("Must not enter while resetting") }
            assertFalse(ChatStore.messages.value.any { it.content.contains("Must not enter") })
        } finally { release.complete(Unit) }
        awaitUntil { !vm.resetting.value }
        assertTrue(fake.wasCancelled)
        assertTrue(ChatStore.messages.value.isEmpty())
        awaitUntil { storedCount() == 0 }
        main.runOnMainSync { vm.submit("Fresh question") }
        awaitUntil { !vm.running.value }
        assertEquals("New visitor", ChatStore.messages.value.last().content)
        assertFalse(fake.historiesSeen.last().any { it.content.contains("Old") })
        awaitUntil { storedCount() == 2 }
    }

    @Test fun resetMenuRequiresConfirmationAndKeepsSavedState() {
        val memories = MemoryStore.memories.value
        val paused = AgentRunState.isPaused
        val automation = AppSettings.fullAutomation
        val decisions = runBlocking { DecisionStore.total() }
        val audit = runBlocking { MobileClawDb.get(app).actionLog().count() }
        val corpus = File("/sdcard/Download/MobileClawDemo/hotel-confirmation.pdf")
        val before = if (corpus.exists()) corpus.readBytes() else null
        ChatStore.add(ChatMessage(ChatMessage.Role.USER, "Keep until confirmed"))
        awaitUntil { storedCount() == 1 }
        ActivityScenario.launch(MainActivity::class.java).use {
            it.onActivity { activity -> activity.findViewById<android.view.View>(R.id.btnMore).performClick() }
            clickText("Reset demo")
            clickText("Cancel")
            assertEquals("Keep until confirmed", ChatStore.messages.value.single().content)
            it.onActivity { activity -> activity.findViewById<android.view.View>(R.id.btnMore).performClick() }
            clickText("Reset demo")
            clickText("Reset")
            awaitUntil { ChatStore.messages.value.isEmpty() && !DemoReset.resetting.value }
            awaitUntil { storedCount() == 0 }
            assertTrue(NotifStore.events.value.isEmpty())
            assertEquals(memories, MemoryStore.memories.value)
            assertEquals(paused, AgentRunState.isPaused)
            assertEquals(automation, AppSettings.fullAutomation)
            assertEquals(decisions, runBlocking { DecisionStore.total() })
            assertEquals(audit, runBlocking { MobileClawDb.get(app).actionLog().count() })
            if (before != null) assertArrayEquals(before, corpus.readBytes())
        }
    }

    @Test fun switchingChatsDuringDrainDoesNotClearTheOtherChat() {
        ChatStore.add(ChatMessage(ChatMessage.Role.USER, "Old demo"))
        val draining = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = CoroutineScope(Dispatchers.Main).launch {
            DemoReset.run(session) { draining.complete(Unit); release.await() }
        }
        runBlocking { draining.await() }
        val other = ChatStore.newSession()
        try {
            ChatStore.add(ChatMessage(ChatMessage.Role.USER, "Other chat is kept"))
            release.complete(Unit)
            runBlocking { job.join() }
            assertEquals("Other chat is kept", ChatStore.messages.value.single().content)
            awaitUntil { storedCount() == 0 }
            ChatStore.switchTo(session)
            assertTrue(ChatStore.messages.value.isEmpty())
        } finally {
            release.complete(Unit)
            ChatStore.switchTo(session)
            ChatStore.deleteSession(other)
        }
    }

    @Test fun failedDrainReleasesTheGateAndAllowsAnotherReset() = runBlocking {
        withContext(Dispatchers.Main) {
            try {
                DemoReset.run(session) { throw IllegalStateException("scripted failure") }
                fail("expected scripted failure")
            } catch (_: IllegalStateException) { }
            assertFalse(DemoReset.resetting.value)
            val proposal = PendingAction.Proposal("Fresh approval") { "fresh" }
            assertTrue(PendingAction.isCurrent(proposal))
            DemoReset.run(session) { }
            assertFalse(DemoReset.resetting.value)
            assertFalse(PendingAction.isCurrent(proposal))
        }
    }

    @Test fun connectedListenerDrainsOldWorkAndReopensTheNextEventWindow() {
        val listener = NotifListener.instance
        assumeNotNull("enable with -e listenerReset true", listener)
        val service = listener!!
        val oldEpoch = DemoReset.generation
        val field = NotifListener::class.java.getDeclaredField("serviceScope").apply { isAccessible = true }
        val scope = field.get(service) as CoroutineScope
        val cooldownField = NotifListener::class.java.getDeclaredField("followUpCooldown").apply { isAccessible = true }
        val cooldown = cooldownField.get(service) as FollowUp.Cooldown
        cooldown.clear()
        assertTrue(cooldown.tryStart("fixture", 1000))
        val started = CompletableDeferred<Unit>()
        val draining = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var oldProposal: PendingAction.Proposal? = null
        scope.launch {
            try { started.complete(Unit); awaitCancellation() }
            finally {
                withContext(NonCancellable) {
                    draining.complete(Unit)
                    release.await()
                    ChatStore.add(ChatMessage(ChatMessage.Role.SYSTEM, "Late background result"))
                    oldProposal = PendingAction.Proposal("Old background approval") { "must not execute" }
                }
            }
        }
        runBlocking { started.await() }
        lateinit var vm: MainViewModel
        main.runOnMainSync { vm = MainViewModel(app) { FakeAiClient { done("unused") } }; vm.resetDemo() }
        try {
            runBlocking { draining.await() }
            assertTrue(DemoReset.resetting.value)
        } finally { release.complete(Unit) }
        awaitUntil { !DemoReset.resetting.value }
        assertTrue(ChatStore.messages.value.isEmpty())
        awaitUntil { storedCount() == 0 }
        assertFalse(PendingAction.isCurrent(oldProposal!!))
        DemoReset.publishIfCurrent(oldEpoch) {
            ChatStore.add(ChatMessage(ChatMessage.Role.SYSTEM, "Detached old listener result"))
        }
        assertTrue("even a detached old listener cannot publish", ChatStore.messages.value.isEmpty())
        assertTrue("reset removes the old app window", cooldown.tryStart("fixture", 1001))
        assertFalse("the ordinary window resumes", cooldown.tryStart("fixture", 1002))
        // Also cover first lazy initialization after reset: an old persisted audit row
        // must not become the next round's prompt history.
        val lazyField = NotifListener::class.java.getDeclaredField("reasoningModuleLazy").apply { isAccessible = true }
        val module = (lazyField.get(service) as Lazy<*>).value!!
        val history = ReasoningModule::class.java.getDeclaredMethod("historyForPrompt").apply { isAccessible = true }
        assertTrue((history.invoke(module) as List<*>).isEmpty())
    }

    @Test fun triageResetDropsDedupAndOldPromptContextWithoutDeletingDecisions() = runBlocking {
        val module = ReasoningModule(apiKey = "")
        val notif = CapturedNotif("demo-reset-test", "fixture", "Fixture", 1,
            "Test", "Test", "", isMessaging = true, isOngoing = false, posted = true)
        module.resetForDemo()
        val first = module.decide(notif)!!
        val count = DecisionStore.total()
        assertSame("the normal dedup window still applies", first, module.decide(notif))
        module.resetForDemo()
        val history = ReasoningModule::class.java.getDeclaredMethod("historyForPrompt").apply { isAccessible = true }
        assertTrue((history.invoke(module) as List<*>).isEmpty())
        val next = module.decide(notif)!!
        assertNotSame("a new round must not reuse the previous decision", first, next)
        assertEquals(1, (history.invoke(module) as List<*>).size)
        assertTrue(DecisionStore.total() >= count)
    }
}
