package edu.pitt.isl.mobileclaw.notiftest.demo

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.pitt.isl.mobileclaw.notiftest.*
import edu.pitt.isl.mobileclaw.notiftest.skills.appfunctions.CallAppFunctionTool
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections

/**
 * Explicitly opt-in real-bridge acceptance: one idempotent theme call and one missing
 * function, both through the production tool and visible confirmation dialog. No LLM,
 * calls, messages, calendar writes, task writes or direct privileged modifying command.
 */
@RunWith(AndroidJUnit4::class)
class AppFunctionConfirmationRunner {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui get() = instrumentation.uiAutomation
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val pkg = CallAppFunctionTool.TASKBOARD_PACKAGE
    private val functionClass = CallAppFunctionTool.TASKBOARD_CLASS

    private fun shell(command: String): String = ui.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
    }

    /** Infrastructure observation only. UiAutomation splits argv without shell quoting. */
    private fun observe(function: String): JSONArray {
        require(function == "getBoard" || function == "listTasks")
        val raw = shell("cmd app_function execute-app-function --package $pkg --function $functionClass#$function --parameters {}")
        check(raw.startsWith("{")) { "$function did not return JSON: $raw" }
        return JSONObject(raw).getJSONArray("androidAppfunctionsReturnValue")
    }

    private fun boardColor(): String = observe("getBoard").getJSONObject(0)
        .getJSONArray("themeColor").getString(0)

    @Test fun realBridgeSuccessAndPlatformFailureAreReportedOnlyAfterApproval() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("confirmationRealBridge") == "true")
        val runId = requireNotNull(args.getString("confirmationRunId"))
        require(runId.matches(Regex("CONFIRM-[A-Z0-9-]{1,40}")))
        assertTrue("Android 16 AppFunctions required", android.os.Build.VERSION.SDK_INT >= 36)
        val output = File(context.filesDir, "demo-confirmation/$runId")
        require(!File(output, "report.json").exists()) { "Use a new run id to preserve previous acceptance evidence" }
        output.mkdirs()
        AppSettings.init(context)
        AgentRunState.init(context)
        ChatStore.init(context)
        ActionLogStore.init(context)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val oldPaused = AgentRunState.isPaused
        val hadPaused = prefs.contains(AgentRunState.KEY_PAUSED)
        val hadFullAutomation = prefs.contains(AppSettings.KEY_FULL_AUTOMATION)
        val oldFullAutomation = AppSettings.fullAutomation
        val hadCalendar = prefs.contains(AppSettings.KEY_CALENDAR_SOURCE)
        val oldCalendar = AppSettings.calendarReadSource
        val originalSession = ChatStore.activeSessionId.value
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        val proposals = Collections.synchronizedList(mutableListOf<PendingAction.Proposal>())
        val descriptions = mutableSetOf<String>()
        val started = SystemClock.elapsedRealtime()
        val startedWall = System.currentTimeMillis()
        var scenario: ActivityScenario<MainActivity>? = null
        var session: String? = null
        var beforeTasks: JSONArray? = null
        var originalColor: String? = null
        val report = JSONObject().put("runId", runId).put("liveLlm", false)
            .put("realShizukuBridge", true).put("taskWrites", 0).put("outgoingMessages", 0)
            .put("calendarWrites", 0).put("approvalViaVisibleDialog", true)
            .put("wifiBefore", Settings.Global.getString(context.contentResolver, "wifi_on"))
            .put("mobileDataBefore", Settings.Global.getString(context.contentResolver, "mobile_data"))

        fun writeReport() = File(output, "report.json").writeText(report.toString(2) + "\n")
        fun phase(value: String) { report.put("phase", value); writeReport() }
        fun screenshot(name: String) {
            // The executor can return before the dialog's dismissal frame is drawn.
            // Wait for the actual settled UI, not merely a published ChatStore row.
            ui.waitForIdle(300, 3_000)
            val bitmap = requireNotNull(ui.takeScreenshot()) { "Screenshot failed: $name" }
            try { File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
        suspend fun audit(): List<ActionLogEntity> = MobileClawDb.get(context).actionLog()
            .rowsBetween(startedWall, System.currentTimeMillis() + 1, 500)
            .filter { it.appName == CallAppFunctionTool.SOURCE }
        fun systemResults() = ChatStore.messages.value.filter { it.role == ChatMessage.Role.SYSTEM }
        fun visibleDialog(description: String): android.view.View? = WindowInspector.getGlobalWindowViews().singleOrNull { root ->
            root.findViewById<TextView>(android.R.id.message)?.let { it.isShown && it.text.toString() == description } == true
        }
        suspend fun stage(name: String, parameters: JSONObject, expectedPriorCalls: Int): PendingAction.Proposal {
            phase("staging-$name")
            assertTrue(AgentRunState.isPaused)
            assertTrue(AppSettings.fullAutomation)
            assertTrue("Only the actual Shizuku backend may run this acceptance", ShellRegistry.current() is ShizukuShell)
            val receipt = CallAppFunctionTool().invokeWithReceipt(JSONObject()
                .put("package", pkg).put("function_id", "$functionClass#$name").put("parameters", parameters))
            assertTrue("The tool must return an application-owned pending receipt", receipt is ToolInvocation.AwaitingConfirmation)
            val description = (receipt as ToolInvocation.AwaitingConfirmation).description
            descriptions.add(description)
            awaitUntil(timeoutMs = 10_000, message = "Production confirmation dialog missing for $name") {
                var shown = false
                instrumentation.runOnMainSync { shown = visibleDialog(description) != null }
                shown
            }
            awaitUntil { synchronized(proposals) { proposals.size == expectedPriorCalls + 1 } }
            val proposal = synchronized(proposals) { proposals.last() }
            assertEquals(description, proposal.description)
            assertFalse("Full Automation must never apply to AppFunctions", proposal.allowFullAutomation)
            // A broken allowFullAutomation flag dispatches a coroutine; do not check
            // immediately and accidentally call an unexecuted coroutine proof of safety.
            delay(250)
            assertEquals("No command audit may appear before approval", expectedPriorCalls, audit().size)
            assertEquals("No SYSTEM result may appear before approval", expectedPriorCalls, systemResults().size)
            assertEquals("Tasks changed before approval", beforeTasks!!.toString(), observe("listTasks").toString())
            assertEquals("Theme changed before approval", originalColor, boardColor())
            report.put("$name-pending", JSONObject().put("description", description)
                .put("typedState", "AwaitingConfirmation").put("fullAutomation", true)
                .put("commandAuditRowsBeforeApproval", expectedPriorCalls)
                .put("systemResultsBeforeApproval", expectedPriorCalls))
            screenshot(if (expectedPriorCalls == 0) "pending" else "failure-pending")
            return proposal
        }
        fun approve(proposal: PendingAction.Proposal) {
            instrumentation.runOnMainSync {
                assertTrue(ShellRegistry.current() is ShizukuShell)
                val root = requireNotNull(visibleDialog(proposal.description)) { "Validated dialog disappeared before approval" }
                val button = root.findViewById<Button>(android.R.id.button1)
                assertTrue(button.isShown)
                assertEquals("Do it", button.text.toString())
                assertTrue(button.performClick())
            }
        }

        try {
            AgentRunState.setPaused(true)
            phase("checking-existing-shizuku-permission")
            awaitUntil(timeoutMs = 10_000) { ShizukuBridge.isShizukuRunning() }
            assertTrue("Grant Shizuku permission separately; this runner never requests a new grant", ShizukuBridge.hasPermission())
            instrumentation.runOnMainSync {
                // Already granted: this only binds the service, without opening a grant dialog.
                assertTrue(ShizukuBridge.hasPermission())
                ShizukuBridge.requestPermission()
            }
            awaitUntil(timeoutMs = 15_000) { ShellRegistry.current() is ShizukuShell }
            beforeTasks = observe("listTasks")
            originalColor = boardColor()
            require(originalColor!!.matches(Regex("#[0-9A-Fa-f]{6}"))) { "Unexpected current theme: $originalColor" }
            report.put("tasksBefore", beforeTasks).put("themeBefore", originalColor)
            prefs.edit().putBoolean(AppSettings.KEY_FULL_AUTOMATION, true).commit()
            awaitUntil { ChatStore.sessions.value.isNotEmpty() }
            withContext(Dispatchers.Main) {
                session = ChatStore.newSession()
                ChatStore.rename(session!!, "Confirmation receipt $runId")
            }
            report.put("sessionId", session)
            assertTrue(ChatStore.messages.value.isEmpty())
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            scenario = ActivityScenario.launch(MainActivity::class.java)
            instrumentation.waitForIdleSync()
            scope.launch(start = CoroutineStart.UNDISPATCHED) { PendingAction.proposals.collect { proposals.add(it) } }

            val success = stage("setThemeColor", JSONObject().put("color", originalColor), 0)
            approve(success)
            phase("waiting-for-success-return")
            awaitUntil(timeoutMs = 30_000) { systemResults().any { it.content.startsWith("Action result: setThemeColor returned:") } }
            awaitUntil(timeoutMs = 10_000) { runBlocking { audit().size == 1 } }
            val successAudit = audit().single()
            assertTrue("Confirmed real success must be recorded as successful", successAudit.success)
            val successText = systemResults().single().content
            assertTrue(successText.contains(originalColor!!))
            assertFalse(successText.contains("✅"))
            report.put("successResult", successText)
            screenshot("success")
            assertEquals(beforeTasks!!.toString(), observe("listTasks").toString())
            assertEquals(originalColor, boardColor())

            val missingName = "confirmationMissingFunction"
            val missing = stage(missingName, JSONObject(), 1)
            approve(missing)
            phase("waiting-for-platform-failure")
            awaitUntil(timeoutMs = 30_000) { systemResults().size == 2 }
            awaitUntil(timeoutMs = 10_000) { runBlocking { audit().size == 2 } }
            val failureAudit = audit().single { it.actionTitle.contains("#$missingName") }
            assertFalse("Platform failure must not be a successful audit even if shell exits zero", failureAudit.success)
            val failureText = systemResults().last().content
            assertTrue("Expected a platform or shell failure, got $failureText",
                failureText.startsWith("Action result: $missingName failed:") ||
                    failureText.startsWith("Action result: Command failed") ||
                    failureText.startsWith("Action result: Action failed:"))
            assertFalse(failureText.contains("✅"))
            assertFalse(failureText.contains("succeeded", true))
            report.put("failureResult", failureText).put("failureAudit", JSONObject()
                .put("success", failureAudit.success).put("result", failureAudit.result))
            screenshot("failure")
            val afterTasks = observe("listTasks")
            val afterColor = boardColor()
            assertEquals("No task may be added, removed or changed", beforeTasks!!.toString(), afterTasks.toString())
            assertEquals("The idempotent theme call must preserve the theme", originalColor, afterColor)
            report.put("tasksAfter", afterTasks).put("themeAfter", afterColor)
                .put("tasksUnchanged", true).put("themeUnchanged", true)
                .put("status", "passed").put("phase", "verified")
        } catch (error: Throwable) {
            report.put("status", "failed").put("error", "${error.javaClass.simpleName}: ${error.message}")
            runCatching { screenshot("runner-error") }
            throw error
        } finally {
            PendingAction.beginDemoReset()
            try {
                scope.cancel()
                try {
                    instrumentation.runOnMainSync {
                        WindowInspector.getGlobalWindowViews().forEach { root ->
                            val description = root.findViewById<TextView>(android.R.id.message)?.text?.toString()
                            if (description in descriptions) root.findViewById<Button>(android.R.id.button2)?.performClick()
                        }
                    }
                    scenario?.close()
                    report.put("chat", JSONArray(ChatStore.messages.value.map { JSONObject().put("role", it.role).put("content", it.content) }))
                    report.put("audit", JSONArray(audit().map { JSONObject().put("action", it.actionTitle)
                        .put("result", it.result).put("success", it.success) }))
                } finally {
                    // An evidence or UI failure must not leave Full Automation enabled.
                    ChatStore.switchTo(originalSession)
                    prefs.edit().apply {
                        if (hadFullAutomation) putBoolean(AppSettings.KEY_FULL_AUTOMATION, oldFullAutomation)
                        else remove(AppSettings.KEY_FULL_AUTOMATION)
                        if (hadCalendar) putString(AppSettings.KEY_CALENDAR_SOURCE, oldCalendar)
                        else remove(AppSettings.KEY_CALENDAR_SOURCE)
                    }.commit()
                    AgentRunState.setPaused(oldPaused)
                    if (!hadPaused) prefs.edit().remove(AgentRunState.KEY_PAUSED).commit()
                    if (!oldPaused) instrumentation.runOnMainSync { NotifListener.instance?.onRunStateResumed() }
                    report.put("settingsRestored", AppSettings.fullAutomation == oldFullAutomation && AppSettings.calendarReadSource == oldCalendar)
                        .put("pausedStateRestored", AgentRunState.isPaused == oldPaused)
                        .put("activeSessionRestored", ChatStore.activeSessionId.value == originalSession)
                        .put("evidenceSessionRetained", session != null)
                }
            } finally {
                PendingAction.finishDemoReset()
                report.put("elapsedMs", SystemClock.elapsedRealtime() - started)
                    .put("wifiAfter", Settings.Global.getString(context.contentResolver, "wifi_on"))
                    .put("mobileDataAfter", Settings.Global.getString(context.contentResolver, "mobile_data"))
                writeReport()
            }
        }
    }
}
