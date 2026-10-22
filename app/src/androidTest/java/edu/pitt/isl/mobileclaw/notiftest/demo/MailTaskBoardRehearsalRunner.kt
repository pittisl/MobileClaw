package edu.pitt.isl.mobileclaw.notiftest.demo

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.pitt.isl.mobileclaw.notiftest.*
import edu.pitt.isl.mobileclaw.notiftest.skills.notifications.ReadNotificationsTool
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Synthetic email or an opt-in real Gmail capture; production model, UI and AppFunctions bus. */
@RunWith(AndroidJUnit4::class)
class MailTaskBoardRehearsalRunner {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui get() = instrumentation.uiAutomation
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val pkg = "edu.pitt.isl.mobileclaw.taskboard"
    private val function = "$pkg.TaskBoardFunctions"
    private val subject = "Demo preparation for October 28"
    // Fits both the foreground context's 120-char preview and read_notifications' 160-char body.
    private val body = "Bring a USB-C HDMI adapter to the booth by Oct 28, 9 AM. Alex handles the poster; no poster task for you."
    private val channel = "demo_mail_taskboard"
    private val notificationId = 62801

    private fun shell(command: String): String = ui.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
    }

    /** Infrastructure observation only. The modifying call must come from the model via the UI. */
    private fun tasks(): JSONArray {
        // UiAutomation tokenizes argv itself; shell quotes would become literal characters.
        val raw = shell("cmd app_function execute-app-function --package $pkg --function $function#listTasks --parameters {}")
        check(raw.startsWith("{")) { "listTasks did not return JSON: $raw" }
        return JSONObject(raw).getJSONArray("androidAppfunctionsReturnValue")
    }

    private fun verifyTask(task: JSONObject, sourceSubject: String = subject) {
        val title = task.getJSONArray("title").getString(0)
        val note = task.getJSONArray("note").getString(0)
        assertTrue("Missing adapter: $title", title.contains("adapter", true))
        assertTrue("Missing USB-C: $title", title.contains("USB-C", true))
        assertTrue("Missing HDMI: $title", title.contains("HDMI", true))
        assertFalse("Wrong assignee: $title", title.contains("poster", true))
        assertTrue("Missing deadline: $note", Regex("(?:Oct(?:ober)?\\.?\\s*28|2026-10-28)", RegexOption.IGNORE_CASE).containsMatchIn(note))
        assertTrue("Missing 9 AM deadline: $note", Regex("(?:9(?::00)?\\s*A\\.?M\\.?|09:00)", RegexOption.IGNORE_CASE).containsMatchIn(note))
        assertTrue("Missing source: $note", ReasoningModule.redact(note).contains(ReasoningModule.redact(sourceSubject), true))
        assertFalse(task.getJSONArray("done").getBoolean(0))
    }

    private fun verifyCalendarScope() {
        DemoCalendar(context).requireSelected()
    }

    @Test fun preflightWithoutModel() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("mailBoardPreflight") == "true")
        assertTrue("Android 16 required", android.os.Build.VERSION.SDK_INT >= 36)
        awaitUntil(timeoutMs = 10_000) { ShizukuBridge.isShizukuRunning() }
        if (!ShizukuBridge.hasPermission()) {
            instrumentation.runOnMainSync { ShizukuBridge.requestPermission() }
            error("Grant MobileClaw the Shizuku permission, then rerun preflight")
        }
        instrumentation.runOnMainSync { ShizukuBridge.requestPermission() }
        awaitUntil(timeoutMs = 15_000) { ShellRegistry.isConnected }
        assertTrue("Fixture would be truncated", body.length <= 120)
        verifyCalendarScope()
        val result = JSONObject().put("status", "passed").put("liveLlm", false)
            .put("bridgeConnected", ShellRegistry.isConnected).put("bridgeLevel", ShellRegistry.current()!!.level)
            .put("existingTasks", tasks()).put("subject", subject).put("body", body)
        File(context.filesDir, "demo-mail-board/preflight.json").apply {
            parentFile!!.mkdirs(); writeText(result.toString(2) + "\n")
        }
    }

    @Test fun emailNotificationCreatesOneTaskThroughConfirmedAppFunction() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("mailBoardRehearsal") == "true")
        val gmailMarker = args.getString("realGmailMarker")?.also {
            require(it.matches(Regex("MCMAIL-[A-Z0-9-]{1,40}")))
        }
        val sourceSubject = gmailMarker ?: subject
        val realGmail = gmailMarker != null
        val captureOnly = args.getString("realGmailCaptureOnly") == "true"
        require(!captureOnly || realGmail) { "Capture-only requires a real Gmail marker" }
        // A receipt-body allowlist is test validation, never an answer supplied to the model.
        val gmailExpectedBody = if (realGmail) requireNotNull(args.getString("realGmailExpectedBody")) {
            "Real Gmail mode requires the exact received test body for input isolation"
        }.also { require(it.isNotBlank() && it.length <= 500) } else null
        val runId = requireNotNull(args.getString("mailBoardRunId"))
        require(runId.matches(Regex("MAILBOARD-[A-Z0-9-]{1,40}")))
        val output = File(context.filesDir, "demo-mail-board/$runId").apply { mkdirs() }
        val report = JSONObject().put("runId", runId).put("source", if (realGmail)
            "real active Gmail notification captured by the production listener during resume/reseed, then test input selection; " +
                (if (captureOnly) "offline production reader checks, no model turn or task write"
                else "explicit foreground request, not live background triage")
            else "synthetic email-category Android notification, posted by MobileClaw instrumentation; no mail server/client")
            .put("mode", if (realGmail) "real-gmail-reseed" else "synthetic-email")
            .put("captureOnly", captureOnly).put("liveLlm", false).put("wroteTask", false)
            .put("subject", sourceSubject).put("sourceSubjectAfterRedaction", ReasoningModule.redact(sourceSubject))
            .put("model", LlmConfig.model)
        if (!realGmail) report.put("body", body)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val keys = listOf(AppSettings.KEY_FULL_AUTOMATION, AppSettings.KEY_REASONING_TRACE, AppSettings.KEY_FOLLOW_UP)
        val saved = keys.associateWith { if (prefs.contains(it)) prefs.getBoolean(it, false) else null }
        val oldPaused = AgentRunState.isPaused
        val nm = context.getSystemService(NotificationManager::class.java)
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        val proposals = java.util.Collections.synchronizedList(mutableListOf<PendingAction.Proposal>())
        var scenario: ActivityScenario<MainActivity>? = null
        var vm: MainViewModel? = null
        val started = SystemClock.elapsedRealtime()
        val startedWall = System.currentTimeMillis()
        fun screenshot(name: String) {
            ui.takeScreenshot()?.let { bitmap ->
                File(output, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        fun phase(value: String) {
            report.put("phase", value)
            File(output, "report.json").writeText(report.toString(2) + "\n")
        }
        fun dismissNegative() = instrumentation.runOnMainSync {
            WindowInspector.getGlobalWindowViews().forEach { root ->
                root.findViewById<Button>(android.R.id.button2)?.takeIf { it.text.toString() == "Not now" }?.performClick()
            }
        }
        try {
            if (realGmail) {
                assertTrue("Real Gmail mode requires the agent already paused", oldPaused)
                assertFalse("Real Gmail mode requires a fresh process without a demo reset", DemoReset.hasReset)
                assertFalse(DemoReset.resetting.value)
            }
            if (!captureOnly) assertTrue(LlmConfig.isConfigured)
            verifyCalendarScope()
            awaitUntil(timeoutMs = 10_000) { ShizukuBridge.hasPermission() }
            instrumentation.runOnMainSync { ShizukuBridge.requestPermission(); AgentRunState.setPaused(true) }
            awaitUntil(timeoutMs = 15_000) { ShellRegistry.isConnected }
            val before = tasks()
            report.put("tasksBefore", before)
            if (realGmail && !captureOnly) {
                assertFalse("A task from this Gmail marker already exists; use a new mail marker", (0 until before.length()).any {
                    ReasoningModule.redact(before.getJSONObject(it).getJSONArray("note").getString(0))
                        .contains(ReasoningModule.redact(sourceSubject), true)
                })
            } else if (!realGmail) {
                assertFalse("A previous fixture task already exists; do not duplicate it", before.toString().contains("HDMI", true))
            }
            prefs.edit().putBoolean(AppSettings.KEY_FULL_AUTOMATION, false)
                .putBoolean(AppSettings.KEY_REASONING_TRACE, true).putBoolean(AppSettings.KEY_FOLLOW_UP, false).commit()
            ui.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            shell("cmd notification allow_listener ${context.packageName}/${NotifListener::class.java.name}")
            instrumentation.runOnMainSync { NotificationListenerService.requestRebind(ComponentName(context, NotifListener::class.java)) }
            awaitUntil(timeoutMs = 15_000) { NotifListener.instance != null }
            awaitUntil(timeoutMs = 10_000) { ChatStore.sessions.value.isNotEmpty() }
            withContext(Dispatchers.Main) {
                val session = ChatStore.newSession()
                ChatStore.rename(session, "Mail to TaskBoard $runId")
                report.put("sessionId", session)
                if (!realGmail) DemoReset.run(session) {}
                else assertTrue("A real Gmail turn must start with empty chat history", ChatStore.messages.value.isEmpty())
            }
            shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { vm = ViewModelProvider(it)[MainViewModel::class.java] }
            scope.launch { PendingAction.proposals.collect { proposals.add(it) } }
            val captured = if (realGmail) {
                phase("selecting-real-gmail-from-production-reseed")
                withContext(Dispatchers.Main) {
                    assertFalse(DemoReset.hasReset)
                    // Reseed suppresses reasoning for all existing keys. Pause again in the
                    // same main-thread turn before any notification callback can run.
                    NotifStore.clear()
                    AgentRunState.setPaused(false)
                    try { NotifListener.instance!!.onRunStateResumed() }
                    finally { AgentRunState.setPaused(true) }
                    val targets = NotifListener.instance!!.activeNotifications.orEmpty().filter { sbn ->
                        sbn.packageName == "com.google.android.gm" &&
                            sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 &&
                            listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT)
                                .any { sbn.notification.extras.getCharSequence(it)?.contains(sourceSubject) == true }
                    }
                    assertEquals("Expected one non-summary real Gmail notification for the marker", 1, targets.size)
                    val target = targets.single()
                    fun normalized(value: String) = value.replace(Regex("\\s+"), " ").trim()
                    val expected = normalized(requireNotNull(gmailExpectedBody))
                    val subjectText = normalized(sourceSubject)
                    val allowedContent = setOf("", expected, subjectText, "$subjectText $expected")
                    val extras = target.notification.extras
                    val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                    val rawText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                    val rawBigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
                    val rawLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }.orEmpty()
                    assertFalse("Reject multi-mail summaries", Regex("\\b(?:new messages|new emails|unread messages)\\b", RegexOption.IGNORE_CASE)
                        .containsMatchIn(listOf(rawTitle, rawText, rawBigText).joinToString("\n")))
                    for (value in listOf(rawText, rawBigText) + rawLines) {
                        assertTrue("Gmail content contains something outside the selected test mail", normalized(value) in allowedContent)
                    }
                    val events = NotifStore.events.value.filter { it.key == target.key && it.posted }
                    assertEquals("Production reseed must capture the actual Gmail notification", 1, events.size)
                    val event = events.single()
                    assertEquals("com.google.android.gm", event.packageName)
                    assertEquals(target.postTime, event.postTimeMs)
                    assertTrue("Marker missing from production capture", "${event.title}\n${event.text}".contains(sourceSubject))
                    assertTrue("Production capture must contain the complete expected test body", normalized(event.text).contains(expected))
                    assertTrue("Production body must contain only the selected test mail", normalized(event.text) in allowedContent)
                    assertFalse(event.isMessaging)
                    report.put("selectedGmailRawText", JSONObject().put("title", rawTitle).put("text", rawText)
                        .put("bigText", rawBigText).put("textLines", JSONArray(rawLines)))
                    // Keep the production object unchanged. Personal notifications never
                    // enter this turn's context or tools; this is explicit test selection.
                    NotifStore.clear()
                    NotifStore.add(event)
                    report.put("testInputSelection", true).put("selectedNotificationKey", target.key)
                        .put("selectedNotificationCount", NotifStore.events.value.size)
                        .put("liveRunningBackgroundTriage", false).put("demoResetHasReset", DemoReset.hasReset)
                    event
                }
            } else {
                withContext(Dispatchers.Main) {
                    AgentRunState.setPaused(false)
                    NotifListener.instance!!.onRunStateResumed()
                    DemoReset.run(ChatStore.activeSessionId.value) {}
                }
                nm.createNotificationChannel(NotificationChannel(channel, "DEMO simulated email", NotificationManager.IMPORTANCE_DEFAULT))
                val postedAt = System.currentTimeMillis()
                phase("posting-synthetic-email")
                nm.notify(notificationId, Notification.Builder(context, channel)
                    .setSmallIcon(android.R.drawable.ic_dialog_email).setContentTitle(subject).setContentText(body)
                    .setSubText("DEMO simulated email • Morgan Demo").setStyle(Notification.BigTextStyle().bigText(body))
                    .setCategory(Notification.CATEGORY_EMAIL).build())
                awaitUntil(timeoutMs = 15_000) {
                    NotifStore.events.value.any { it.title == subject && it.text.contains(body) && it.postTimeMs >= postedAt }
                }
                NotifStore.events.value.last { it.title == subject && it.postTimeMs >= postedAt }.also { assertEquals("email", it.category) }
            }
            report.put("notification", captured.toJSONObject()).put("captureMs", SystemClock.elapsedRealtime() - started)
            if (captureOnly) {
                phase("checking-offline-production-notification-readers")
                val foregroundContext = withContext(Dispatchers.Main) {
                    assertTrue(AgentRunState.isPaused)
                    assertFalse(DemoReset.hasReset)
                    assertEquals(listOf(captured), NotifStore.events.value)
                    assertTrue(ChatStore.messages.value.isEmpty())
                    // Inspect the actual production rendering without opening a model turn
                    // or adding a public app API solely for this instrumentation runner.
                    MainViewModel::class.java.getDeclaredMethod("buildNotifContext").apply { isAccessible = true }
                        .invoke(vm!!) as String
                }
                val reader = ReadNotificationsTool().invoke(JSONObject().put("package_filter", "com.google.android.gm"))
                val fullBody = ReasoningModule.redact(captured.text.replace('\n', ' '))
                val preview120 = ReasoningModule.redact(captured.text.take(120).replace('\n', ' '))
                val preview160 = ReasoningModule.redact(captured.text.take(160).replace('\n', ' '))
                report.put("fullBodyAfterRedaction", fullBody).put("foregroundBody120", preview120)
                    .put("foregroundNotificationContext", foregroundContext).put("readNotificationsBody160", preview160)
                    .put("readNotificationsResult", reader)
                    .put("foregroundComplete", fullBody == preview120).put("readerComplete", fullBody == preview160)
                assertEquals("Foreground 120-char preview would truncate this real test mail", fullBody, preview120)
                assertEquals("Notification reader 160-char preview would truncate this real test mail", fullBody, preview160)
                assertTrue("Production foreground context omitted the test body", foregroundContext.contains(preview120))
                assertTrue("Production notification reader omitted the test body", reader.contains(preview160))
                assertTrue("Production foreground context omitted the source", foregroundContext.contains(ReasoningModule.redact(sourceSubject)))
                assertTrue("Production reader omitted the source", reader.contains(ReasoningModule.redact(sourceSubject)))
                val after = tasks()
                assertEquals("Capture-only must not modify TaskBoard", before.toString(), after.toString())
                assertTrue("Capture-only must not generate modifying proposals", synchronized(proposals) { proposals.isEmpty() })
                report.put("tasksAfter", after).put("existingTasksPreserved", true)
                    .put("status", "passed").put("phase", "offline-capture-verified")
                return@runBlocking
            }
            // End background triage before the explicit user instruction; keep the captured input.
            if (!realGmail) withContext(Dispatchers.Main) {
                AgentRunState.setPaused(true)
                NotifListener.instance!!.beginDemoReset()
                NotifListener.instance!!.drainDemoRuns()
                NotifListener.instance!!.finishDemoReset()
                PendingAction.resetForDemo()
            }
            dismissNegative()
            val request = if (realGmail)
                "Read the Gmail notification for email subject $sourceSubject and add only my action items to TaskBoard. Include the deadline and email subject as the source in the notes. Ask me to confirm before adding anything."
                else "Read the latest demo preparation email notification and add only my action items to TaskBoard. Include the deadline and email subject as the source in the notes. Ask me to confirm before adding anything."
            report.put("request", request)
            phase("waiting-for-model-proposal")
            instrumentation.runOnMainSync {
                if (realGmail) {
                    assertTrue(AgentRunState.isPaused)
                    assertFalse(DemoReset.hasReset)
                    assertEquals(listOf(captured), NotifStore.events.value)
                    assertTrue(ChatStore.messages.value.isEmpty())
                }
                report.put("liveLlm", true)
                vm!!.submit(request)
            }
            awaitUntil(timeoutMs = 90_000, message = "No TaskBoard addTask proposal") {
                synchronized(proposals) { proposals.any { it.description.startsWith("Call app function in $pkg:") && it.description.contains("addTask") } }
            }
            awaitUntil(timeoutMs = 20_000) { !vm!!.running.value }
            val pending = synchronized(proposals) { proposals.toList() }
            assertEquals("Approve exactly one addTask and no unrelated actions", 1, pending.size)
            val proposal = pending.single()
            assertFalse(proposal.allowFullAutomation)
            val description = proposal.description
            val parameters = JSONObject(description.substringAfter("addTask "))
            verifyTask(JSONObject().put("title", JSONArray().put(parameters.getString("title")))
                .put("note", JSONArray().put(parameters.getString("note"))).put("done", JSONArray().put(false)), sourceSubject)
            report.put("proposal", description).put("confirmationMs", SystemClock.elapsedRealtime() - started)
            assertEquals("No task may appear before approval", before.toString(), tasks().toString())
            ui.waitForIdle(500, 3_000)
            screenshot("confirmation")
            var approved = false
            instrumentation.runOnMainSync {
                val roots = WindowInspector.getGlobalWindowViews().filter { root ->
                    root.findViewById<TextView>(android.R.id.message)?.let { it.isShown && it.text.toString() == description } == true
                }
                assertEquals("Expected one visible, validated confirmation", 1, roots.size)
                val button = roots.single().findViewById<Button>(android.R.id.button1)
                assertEquals("Do it", button.text.toString())
                approved = button.performClick()
            }
            assertTrue(approved)
            report.put("approvedViaVisibleDialog", true).put("wroteTask", true)
            phase("waiting-for-real-appfunction-result")
            awaitUntil(timeoutMs = 30_000) {
                ChatStore.messages.value.any { it.role == ChatMessage.Role.SYSTEM && it.content.contains("addTask returned:") }
            }
            val after = tasks()
            assertEquals(before.length() + 1, after.length())
            for (i in 0 until before.length()) assertEquals("Existing task changed", before.getJSONObject(i).toString(), after.getJSONObject(i).toString())
            val added = after.getJSONObject(after.length() - 1)
            verifyTask(added, sourceSubject)
            report.put("tasksAfter", after).put("addedTask", added).put("existingTasksPreserved", true)
                .put("resultMs", SystemClock.elapsedRealtime() - started)
            screenshot("result")
            report.put("status", "passed")
        } catch (t: Throwable) {
            report.put("status", "failed").put("error", "${t.javaClass.simpleName}: ${t.message}")
            screenshot("failure")
            throw t
        } finally {
            PendingAction.beginDemoReset()
            var drained = false
            try {
                instrumentation.runOnMainSync { AgentRunState.setPaused(true); vm?.cancel() }
                withContext(Dispatchers.Main) {
                    val jobs = vm?.viewModelScope?.coroutineContext?.get(Job)?.children?.toList().orEmpty()
                    jobs.forEach { it.cancel() }; jobs.forEach { it.join() }
                    NotifListener.instance?.beginDemoReset(); NotifListener.instance?.drainDemoRuns(); NotifListener.instance?.finishDemoReset()
                }
                drained = true
                dismissNegative()
            } finally {
                scope.cancel()
                if (!realGmail) nm.cancel(notificationId)
                val edit = prefs.edit()
                saved.forEach { (key, value) ->
                    if (!drained && key == AppSettings.KEY_FULL_AUTOMATION) edit.putBoolean(key, false)
                    else if (value == null) edit.remove(key) else edit.putBoolean(key, value)
                }
                edit.commit()
                if (drained) {
                    PendingAction.finishDemoReset()
                    instrumentation.runOnMainSync { AgentRunState.setPaused(oldPaused); if (!oldPaused) NotifListener.instance?.onRunStateResumed() }
                }
                report.put("cleanupDrained", drained).put("settingsRestored", keys.all {
                    if (saved[it] == null) !prefs.contains(it) else prefs.getBoolean(it, false) == saved[it]
                }).put("pausedStateRestored", AgentRunState.isPaused == oldPaused)
            }
            report.put("chat", JSONArray(ChatStore.messages.value.map { JSONObject().put("role", it.role).put("content", it.content) }))
            val audit = MobileClawDb.get(context).actionLog().rowsBetween(startedWall, System.currentTimeMillis() + 1, 500)
            report.put("audit", JSONArray(audit.map { JSONObject().put("source", it.appName).put("action", it.actionTitle).put("success", it.success) }))
            val outbound = audit.count { it.success && (it.appName == "send_sms" || it.actionTitle.contains("reply", true)) }
            val calendarWrites = audit.count { it.success && it.appName == "create_event" }
            report.put("successfulOutboundAuditRows", outbound).put("successfulCalendarWriteAuditRows", calendarWrites)
            report.put("elapsedMs", SystemClock.elapsedRealtime() - started)
            File(output, "report.json").writeText(report.toString(2) + "\n")
            File(output, "trace.log").writeText(shell("logcat -d -v brief --pid=${android.os.Process.myPid()} -s MobileClaw-TRACE:I") + "\n")
            scenario?.close()
            assertTrue("Cleanup failed", drained)
            assertEquals("Unexpected outgoing message", 0, outbound)
            assertEquals("Unexpected calendar write", 0, calendarWrites)
        }
    }
}
