package edu.pitt.isl.mobileclaw.notiftest

import android.app.Application
import android.content.DialogInterface
import android.content.SharedPreferences
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.pitt.isl.mobileclaw.notiftest.skills.appfunctions.CallAppFunctionTool
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * Real tool, confirmation dialog and result publication, with the privileged transport
 * replaced before any call is staged. No model request or TaskBoard operation runs.
 * Each case owns one temporary chat and preserves the user's other conversations.
 */
@RunWith(AndroidJUnit4::class)
class ConfirmationReceiptTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var app: Application
    private lateinit var prefs: SharedPreferences
    private lateinit var session: String
    private lateinit var originalSession: String
    private var originalShell: PrivilegedShell? = null
    private var originalPaused = false
    private var originalFullAutomation = false
    private var hadPausedPreference = false
    private var hadFullAutomationPreference = false
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var shell: FakeShell
    private var startedAt = 0L

    private class FakeShell : PrivilegedShell {
        override val level = ShellLevel.SHELL
        val commands = Collections.synchronizedList(mutableListOf<List<String>>())
        var result = ShellResult(0, SUCCESS_RETURN, "")
        var failure: Exception? = null

        override suspend fun exec(argv: List<String>): ShellResult {
            commands.add(argv.toList())
            failure?.let { throw it }
            return result
        }

        override suspend fun write(path: String, content: String): ShellResult =
            throw AssertionError("An AppFunction must not use the file-write transport")

        override suspend fun isAlive() = true
    }

    @Before fun prepareIsolatedChatAndFakeTransport() {
        app = ApplicationProvider.getApplicationContext()
        prefs = PreferenceManager.getDefaultSharedPreferences(app)
        AppSettings.init(app)
        AgentRunState.init(app)
        ChatStore.init(app)
        originalShell = ShellRegistry.current()
        originalPaused = AgentRunState.isPaused
        originalFullAutomation = AppSettings.fullAutomation
        hadPausedPreference = prefs.contains(AgentRunState.KEY_PAUSED)
        hadFullAutomationPreference = prefs.contains(AppSettings.KEY_FULL_AUTOMATION)
        AgentRunState.setPaused(true)
        prefs.edit().putBoolean(AppSettings.KEY_FULL_AUTOMATION, false).commit()
        originalSession = ChatStore.activeSessionId.value
        session = ChatStore.newSession()
        awaitUntil(message = "temporary chat was not persisted") {
            ChatStore.sessions.value.any { it.id == session }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        instrumentation.waitForIdleSync()
        awaitUntil(message = "MainActivity did not enter the foreground") { AppForeground.isForeground }
        shell = FakeShell()
        ShellRegistry.install(shell)
        startedAt = System.currentTimeMillis()
    }

    @After fun restoreUserState() {
        // Invalidate callbacks before closing the Activity; teardown must not approve.
        PendingAction.resetForDemo()
        scenario?.close()
        ShellRegistry.install(originalShell)
        if (::originalSession.isInitialized) ChatStore.switchTo(originalSession)
        if (::session.isInitialized) ChatStore.deleteSession(session)
        if (::prefs.isInitialized) {
            AgentRunState.setPaused(originalPaused)
            prefs.edit().apply {
                if (hadFullAutomationPreference) putBoolean(AppSettings.KEY_FULL_AUTOMATION, originalFullAutomation)
                else remove(AppSettings.KEY_FULL_AUTOMATION)
                if (!hadPausedPreference) remove(AgentRunState.KEY_PAUSED)
            }.commit()
            if (!originalPaused) instrumentation.runOnMainSync {
                NotifListener.instance?.onRunStateResumed()
            }
        }
    }

    @Test fun anAppFunctionWaitsForTheVisibleApprovalBeforePublishingItsRealReturn() {
        val dialog = stageActualTool()
        assertTrue("a proposal is not an execution result", ChatStore.messages.value.isEmpty())
        assertEquals(0, shell.commands.size)

        click(dialog, DialogInterface.BUTTON_POSITIVE)

        val published = awaitResult()
        assertEquals(1, shell.commands.size)
        assertEquals("cmd", shell.commands.single().first())
        assertTrue(published.contains("addTask returned:"))
        assertTrue(published.contains("id: 42"))
        assertTrue(published.contains("title: Receipt test task"))
        assertFalse(published.contains("✅"))
        assertAuditSuccess(true)
    }

    @Test fun fullAutomationStillCannotSkipTheAppFunctionApproval() {
        prefs.edit().putBoolean(AppSettings.KEY_FULL_AUTOMATION, true).commit()
        assertTrue(AppSettings.fullAutomation)

        val dialog = stageActualTool()
        // Give the ordinary Full Automation coroutine a chance to run if the
        // AppFunction proposal accidentally lost its allowFullAutomation=false flag.
        Thread.sleep(200)
        assertEquals("full automation must not execute the function", 0, shell.commands.size)
        assertTrue(ChatStore.messages.value.isEmpty())

        click(dialog, DialogInterface.BUTTON_POSITIVE)
        assertTrue(awaitResult().contains("addTask returned:"))
        assertEquals(1, shell.commands.size)
    }

    @Test fun decliningTheVisibleDialogNeverExecutesAndPublishesNotExecuted() {
        val dialog = stageActualTool()

        click(dialog, DialogInterface.BUTTON_NEGATIVE)

        awaitUntil(message = "decline was not reported") {
            ChatStore.messages.value.any { it.content == "Not executed: approval declined." }
        }
        assertEquals(0, shell.commands.size)
        assertFalse(ChatStore.messages.value.any { it.content.contains("✅") })
        assertFalse(ChatStore.messages.value.any { it.content.contains("addTask returned:") })
    }

    @Test fun aShellFailureIsPublishedAsFailureWithoutASuccessCheckmark() {
        shell.result = ShellResult(7, "", "receipt-test transport refused")

        click(stageActualTool(), DialogInterface.BUTTON_POSITIVE)

        val published = awaitResult()
        assertEquals(1, shell.commands.size)
        assertTrue(published.contains("Command failed (exit 7)"))
        assertTrue(published.contains("receipt-test transport refused"))
        assertFalse(published.contains("✅"))
        assertFalse(published.contains("addTask returned:"))
        assertAuditSuccess(false)
    }

    @Test fun anExitZeroPlatformErrorIsNotPresentedAsSuccessfulExecution() {
        shell.result = ShellResult(
            0,
            "Error executing app function: android.app.appfunctions.AppFunctionException: The app function is disabled (code 1002). See logcat for more details.",
            ""
        )

        click(stageActualTool(), DialogInterface.BUTTON_POSITIVE)

        val published = awaitResult()
        assertEquals(1, shell.commands.size)
        assertTrue(published.contains("addTask failed:"))
        assertTrue(published.contains("disabled"))
        assertFalse(published.contains("✅"))
        assertFalse(published.contains("addTask returned:"))
        assertAuditSuccess(false)
    }

    @Test fun anExecutorExceptionIsReportedWithoutCrashingOrClaimingSuccess() {
        shell.failure = IllegalStateException("receipt-test executor broke")

        click(stageActualTool(), DialogInterface.BUTTON_POSITIVE)

        val published = awaitResult()
        assertEquals(1, shell.commands.size)
        assertTrue(published.contains("Action failed: receipt-test executor broke"))
        assertFalse(published.contains("✅"))
        assertFalse(published.contains("addTask returned:"))
    }

    @Test fun anUnknownPlatformResponseIsNeutralAndNotAuditedAsSuccess() {
        shell.result = ShellResult(0, "unexpected response", "")
        click(stageActualTool(), DialogInterface.BUTTON_POSITIVE)
        assertEquals("Action result: unexpected response", awaitResult())
        assertEquals(1, shell.commands.size)
        assertAuditSuccess(false)
    }

    @Test fun resetRefusesANewTypedProposalInsteadOfClaimingToAwaitApproval() = runBlocking {
        PendingAction.beginDemoReset()
        try {
            val receipt = CallAppFunctionTool().invokeWithReceipt(JSONObject()
                .put("package", CallAppFunctionTool.TASKBOARD_PACKAGE)
                .put("function_id", "${CallAppFunctionTool.TASKBOARD_CLASS}#addTask")
                .put("parameters", JSONObject().put("title", "never run").put("note", "")))
            assertTrue(receipt is ToolInvocation.NotExecuted)
            assertEquals(0, shell.commands.size)
        } finally { PendingAction.finishDemoReset() }
    }

    @Test fun ordinaryFullAutomationStillPublishesOnlyTheActualExecutorResult() {
        prefs.edit().putBoolean(AppSettings.KEY_FULL_AUTOMATION, true).commit()
        val executions = java.util.concurrent.atomic.AtomicInteger()
        assertTrue(PendingAction.propose(PendingAction.Proposal("ordinary auto test") {
            executions.incrementAndGet(); "actual auto result"
        }))
        awaitUntil { ChatStore.messages.value.any { it.content == "Auto action result: actual auto result" } }
        assertEquals(1, executions.get())
    }

    @Test fun notificationApprovalAlsoPublishesOnlyTheActualResult() = runBlocking {
        shell.result = ShellResult(7, "", "notification transport refused")
        val proposal = ShellGate.proposalFor("call_app_function", "app_function",
            listOf("cmd", "app_function", "execute-app-function", "--package", CallAppFunctionTool.TASKBOARD_PACKAGE,
                "--function", "${CallAppFunctionTool.TASKBOARD_CLASS}#addTask", "--parameters", "{}"), "notification receipt test")
        assertTrue(NotifConfirm.tryShow(proposal))
        val nm = app.getSystemService(android.app.NotificationManager::class.java)
        awaitUntil { nm.activeNotifications.any { it.notification.channelId == NotifConfirm.CHANNEL_ID } }
        val posted = nm.activeNotifications.single { it.notification.channelId == NotifConfirm.CHANNEL_ID }
        assertEquals(0, shell.commands.size)
        assertSame(shell, ShellRegistry.current())
        posted.notification.actions.single { it.title.toString() == "Approve" }.actionIntent.send()
        val result = awaitResult()
        assertTrue(result.contains("Command failed (exit 7)")); assertFalse(result.contains("✅"))
        assertEquals(1, shell.commands.size)
    }

    @Test fun notificationRejectionDoesNotExecuteOrPublishSuccess() {
        assertTrue(NotifConfirm.tryShow(PendingAction.Proposal("notification decline test", allowFullAutomation = false) {
            error("Declining must never execute")
        }))
        val nm = app.getSystemService(android.app.NotificationManager::class.java)
        awaitUntil { nm.activeNotifications.any { it.notification.channelId == NotifConfirm.CHANNEL_ID } }
        val posted = nm.activeNotifications.single { it.notification.channelId == NotifConfirm.CHANNEL_ID }
        posted.notification.actions.single { it.title.toString() == "Reject" }.actionIntent.send()
        awaitUntil { ChatStore.messages.value.any { it.content == "Not executed: approval declined." } }
        assertEquals(0, shell.commands.size)
    }

    @Test fun resetMakesAnAlreadyVisibleApprovalInert() {
        val dialog = stageActualTool()
        PendingAction.resetForDemo()

        // The old visible dialog still holds its original callback. Even pressing
        // it after reset must fail the generation check immediately before execution.
        click(dialog, DialogInterface.BUTTON_POSITIVE)
        instrumentation.waitForIdleSync()
        Thread.sleep(100)

        assertEquals(0, shell.commands.size)
        assertTrue("an obsolete result must not recreate a chat bubble", ChatStore.messages.value.isEmpty())
    }

    private fun stageActualTool(): AlertDialog {
        assertSame("the test must never stage against the real bridge", shell, ShellRegistry.current())
        val args = JSONObject()
            .put("package", CallAppFunctionTool.TASKBOARD_PACKAGE)
            .put("function_id", "${CallAppFunctionTool.TASKBOARD_CLASS}#addTask")
            .put("parameters", JSONObject().put("title", "Receipt test task").put("note", "Fake transport only"))
        val tool = CallAppFunctionTool()
        assertTrue(tool.requiresConfirmationReceipt)
        val receipt = runBlocking { tool.invokeWithReceipt(args) }
        assertTrue("the real tool must return a typed deferred receipt", receipt is ToolInvocation.AwaitingConfirmation)
        assertTrue(receipt.text.contains("waiting for your confirmation"))
        assertEquals("staging must not cross the bridge", 0, shell.commands.size)

        var dialog: AlertDialog? = null
        awaitUntil(message = "the actual confirmation dialog did not become visible") {
            scenario!!.onActivity { activity ->
                val field = MainActivity::class.java.getDeclaredField("actionDialogs").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                val dialogs = field.get(activity) as Set<AlertDialog>
                dialog = dialogs.singleOrNull { it.isShowing && it.window?.decorView?.isShown == true }
            }
            dialog != null
        }
        return dialog!!
    }

    private fun click(dialog: AlertDialog, which: Int) {
        scenario!!.onActivity {
            assertSame("the test must never approve against the real bridge", shell, ShellRegistry.current())
            assertTrue("the confirmation must be visible", dialog.isShowing)
            val button = dialog.getButton(which)
            assertTrue(button.isShown)
            assertEquals(if (which == DialogInterface.BUTTON_POSITIVE) "Do it" else "Not now", button.text.toString())
            assertTrue(button.performClick())
        }
        instrumentation.waitForIdleSync()
    }

    private fun awaitResult(): String {
        awaitUntil(message = "the confirmed execution result was not published") {
            ChatStore.messages.value.any { it.role == ChatMessage.Role.SYSTEM && it.content.startsWith("Action result: ") }
        }
        return ChatStore.messages.value.single { it.role == ChatMessage.Role.SYSTEM }.content
    }

    private fun assertAuditSuccess(expected: Boolean) {
        var results = emptyList<Boolean>()
        awaitUntil(message = "the actual command result was not audited") {
            results = runBlocking { MobileClawDb.get(app).actionLog().rowsBetween(startedAt, System.currentTimeMillis() + 1, 500) }
                .filter { it.appName == "call_app_function" }.map { it.success }
            results.isNotEmpty()
        }
        assertEquals(listOf(expected), results)
    }

    companion object {
        private const val SUCCESS_RETURN = """{"androidAppfunctionsReturnValue":[{"id":[42],"title":["Receipt test task"],"note":["Fake transport only"],"done":[false]}]}"""
    }
}
