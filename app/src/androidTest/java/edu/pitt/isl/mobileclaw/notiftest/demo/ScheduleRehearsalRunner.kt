package edu.pitt.isl.mobileclaw.notiftest.demo

import android.Manifest
import android.app.PendingIntent
import android.content.*
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.view.inspector.WindowInspector
import android.widget.Button
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.pitt.isl.mobileclaw.notiftest.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in live model rehearsal. Every proposed action is observed and revoked, never approved. */
@RunWith(AndroidJUnit4::class)
class ScheduleRehearsalRunner {
    private val expectedIntervals = listOf(
        "Wed 28 Oct 2026, 13:30 – Wed 28 Oct 2026, 14:30 — Poster review",
        "Wed 28 Oct 2026, 14:30 – Wed 28 Oct 2026, 15:00 — Demo setup",
        "Wed 28 Oct 2026, 15:30 – Wed 28 Oct 2026, 16:30 — Research meeting"
    )
    private val alternativeSlot = Regex(
        "(?<![\\d:])(?:15:00\\s*(?:[-–—]|to)\\s*15:30|3(?::00)?\\s*(?:p\\.?m\\.?)?\\s*(?:[-–—]|to)\\s*3:30\\s*(?:p\\.?m\\.?)?)(?![\\d:])",
        RegexOption.IGNORE_CASE)
    private fun verifyScheduleIntervals(text: String) {
        listOf("14:30–15:30", "13:00–13:30", "15:00–16:00", "3:00 AM–3:30 AM").forEach {
            assertFalse("Slot matcher admitted wrong interval: $it", alternativeSlot.containsMatchIn(it))
        }
        expectedIntervals.forEach { assertTrue("Calendar interval missing: $it", text.contains(it)) }
    }
    /** Explicit setup: leaves the visible Calendar to read preference on the owned fixture. */
    @Test fun selectAndVerifyDemoCalendarWithoutModel() = runBlocking<Unit> {
        assumeTrue("Opt in with -e scheduleCalendarScope true",
            InstrumentationRegistry.getArguments().getString("scheduleCalendarScope") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val demoCalendar = DemoCalendar(context)
        val calendarId = demoCalendar.select()
        val result = edu.pitt.isl.mobileclaw.notiftest.skills.calendar.ReadCalendarTool(context).invoke(
            JSONObject().put("range_start", "2026-10-28T00:00:00").put("range_end", "2026-10-28T23:59:59"))
        demoCalendar.requiredFixtures("schedule", listOf("Poster review", "Demo setup", "Research meeting")).forEach {
            assertTrue("Schedule fixture interval missing", result.contains(it.renderedInterval()))
        }
        val out = File(context.filesDir, "demo-schedule/calendar-scope.json")
        out.parentFile!!.mkdirs()
        out.writeText(JSONObject().put("calendarId", calendarId).put("result", result)
            .put("liveLlm", false).put("status", "passed").toString(2) + "\n")
    }

    @Test fun incomingSmsFindsNextFreeSlot() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with -e scheduleRehearsal true", args.getString("scheduleRehearsal") == "true")
        val runId = requireNotNull(args.getString("scheduleRunId"))
        require(runId.matches(Regex("SCHEDULE-[A-Z0-9-]{1,40}")))
        val recordingHoldMs = args.getString("scheduleRecordingHoldMs")?.toLong() ?: 0L
        require(recordingHoldMs in 0L..8_000L)
        val recordingHome = args.getString("scheduleRecordingHome") == "true"
        require(!recordingHome || recordingHoldMs > 0)
        val shortRequest = args.getString("scheduleShortRequest") == "true"
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ui = instrumentation.uiAutomation
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sender = "+12025550144"
        val body = "Can we sync Oct 28, 2-2:30pm? If busy, suggest the next 30-min slot."
        val output = File(context.filesDir, "demo-schedule/$runId")
        output.mkdirs()
        val report = JSONObject().put("runId", runId).put("sender", sender).put("body", body)
            .put("noApprovalPerformed", true).put("model", LlmConfig.model).put("recordingHoldMs", recordingHoldMs)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val keys = listOf(AppSettings.KEY_FULL_AUTOMATION, AppSettings.KEY_REASONING_TRACE, AppSettings.KEY_FOLLOW_UP)
        val saved = keys.associateWith { if (prefs.contains(it)) prefs.getBoolean(it, false) else null }
        val oldPaused = AgentRunState.isPaused
        val proposals = java.util.Collections.synchronizedList(mutableListOf<String>())
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        var scenario: ActivityScenario<MainActivity>? = null
        var vm: MainViewModel? = null
        var registered = false
        val result = CompletableDeferred<Int>()
        val action = "${context.packageName}.SCHEDULE.$runId"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) { result.complete(resultCode) }
        }
        fun shell(command: String) = ui.executeShellCommand(command).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
        }
        fun screenshot(name: String) {
            ui.takeScreenshot()?.let { bitmap ->
                File(output, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        fun dismissOnlyNegativeButtons() = instrumentation.runOnMainSync {
            WindowInspector.getGlobalWindowViews().forEach { root ->
                root.findViewById<Button>(android.R.id.button2)?.takeIf { it.text.toString() == "Not now" }?.performClick()
            }
        }
        val started = SystemClock.elapsedRealtime()
        val startedWall = System.currentTimeMillis()
        val phaseTimings = JSONArray()
        report.put("startedWallMs", startedWall).put("phaseTimings", phaseTimings)
        fun phase(name: String) {
            phaseTimings.put(JSONObject().put("name", name).put("elapsedMs", SystemClock.elapsedRealtime() - started))
            report.put("phase", name)
            File(output, "report.json").writeText(report.toString(2) + "\n")
            android.util.Log.i("MobileClaw-SCHEDULE", "$runId $name")
        }
        suspend fun holdForRecording(name: String) {
            phase("$name-visible")
            delay(recordingHoldMs)
            phase("$name-hold-finished")
        }
        android.util.Log.i("MobileClaw-SCHEDULE", "BEGIN $runId")
        try {
            assertTrue("No LLM configured", LlmConfig.isConfigured)
            val demoCalendar = DemoCalendar(context)
            demoCalendar.requireSelected()
            val calendarCheck = edu.pitt.isl.mobileclaw.notiftest.skills.calendar.ReadCalendarTool(context).invoke(
                JSONObject().put("range_start", "2026-10-28T00:00:00").put("range_end", "2026-10-28T23:59:59"))
            demoCalendar.requiredFixtures("schedule", listOf("Poster review", "Demo setup", "Research meeting")).forEach {
                assertTrue("Schedule fixture interval missing", calendarCheck.contains(it.renderedInterval()))
            }
            verifyScheduleIntervals(calendarCheck)
            report.put("calendarScope", AppSettings.calendarReadSource).put("calendarPreflight", calendarCheck)
            prefs.edit().putBoolean(AppSettings.KEY_FULL_AUTOMATION, false)
                .putBoolean(AppSettings.KEY_REASONING_TRACE, true).putBoolean(AppSettings.KEY_FOLLOW_UP, true).commit()
            instrumentation.runOnMainSync { AgentRunState.setPaused(true) }
            shell("cmd notification allow_listener ${context.packageName}/${NotifListener::class.java.name}")
            instrumentation.runOnMainSync { NotificationListenerService.requestRebind(ComponentName(context, NotifListener::class.java)) }
            awaitUntil(timeoutMs = 15_000) { NotifListener.instance != null }
            awaitUntil(timeoutMs = 10_000) { ChatStore.sessions.value.isNotEmpty() }
            withContext(Dispatchers.Main) {
                val session = ChatStore.newSession()
                ChatStore.rename(session, "Schedule rehearsal $runId")
                report.put("sessionId", session)
                DemoReset.run(session) {}
            }
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { vm = ViewModelProvider(it)[MainViewModel::class.java] }
            scope.launch { PendingAction.proposals.collect { proposals.add(it.description) } }
            withContext(Dispatchers.Main) {
                AgentRunState.setPaused(false)
                NotifListener.instance!!.onRunStateResumed() // Baseline BEFORE injection only.
                // Reseed marks old keys stale. Reset AFTER it so a newer postTime on
                // the same SMS key is admitted on repeated rehearsals.
                DemoReset.run(ChatStore.activeSessionId.value) {}
            }
            assertFalse(AppSettings.fullAutomation)
            assertNull("Fictional rehearsal must not inject personal memory",
                MemoryInjection.buildBlock(MemoryStore.memories.value))
            context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_EXPORTED)
            registered = true
            val callback = PendingIntent.getBroadcast(context, runId.hashCode(), Intent(action).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val hex = body.toByteArray(Charsets.UTF_8).joinToString("") { "%02X".format(it) }
            val query = "content query --uri content://sms/inbox --projection _id:address:body:date --where hex(body)='$hex'"
            report.put("inboxBefore", shell(query))
            if (recordingHome) {
                shell("input keyevent KEYCODE_HOME")
                phase("home-before-incoming")
                delay(2_000)
            }
            val injectedWall = System.currentTimeMillis()
            phase("injecting")
            ui.adoptShellPermissionIdentity(Manifest.permission.MODIFY_PHONE_STATE, Manifest.permission.READ_PHONE_STATE)
            try {
                val subs = context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
                val preferred = SubscriptionManager.getDefaultSmsSubscriptionId()
                val subId = subs.firstOrNull { it.subscriptionId == preferred }?.subscriptionId
                    ?: subs.firstOrNull()?.subscriptionId ?: error("No active SIM")
                report.put("subscriptionId", subId)
                context.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
                    .injectSmsPdu(DemoSmsPdu.deliverPdu(sender, body), "3gpp", callback)
            } finally {
                // The live model/tools must run with the app's real permissions.
                ui.dropShellPermissionIdentity()
            }
            report.put("injectionResultCode", withTimeout(20_000) { result.await() })
            assertEquals(Telephony.Sms.Intents.RESULT_SMS_HANDLED, report.getInt("injectionResultCode"))
            report.put("inboxAfter", shell(query))
            phase("waiting-for-live-capture")
            awaitUntil(timeoutMs = 20_000, message = "No live SMS capture") {
                NotifStore.events.value.any { it.packageName == "com.android.messaging" && it.text.contains(body) && it.postTimeMs >= injectedWall }
            }
            val captured = NotifStore.events.value.last { it.text.contains(body) && it.postTimeMs >= injectedWall }
            report.put("notificationKey", captured.key).put("captureMode", "Running; no post-injection reseed")
                .put("captureMs", SystemClock.elapsedRealtime() - started)
            holdForRecording("incoming")
            if (recordingHome) {
                // Bring back the same Activity/ViewModel after showing the native heads-up on Home.
                shell("am start -W --activity-reorder-to-front -n ${context.packageName}/.MainActivity")
                ui.waitForIdle(500, 3_000)
                phase("app-opened")
            }
            phase("waiting-for-triage")
            val decisionDao = MobileClawDb.get(context).actionDecisions()
            awaitUntil(timeoutMs = 120_000, message = "No new triage decision") {
                runBlocking(Dispatchers.IO) {
                    decisionDao.rowsBetween(injectedWall, System.currentTimeMillis() + 1, 50)
                        .any { it.notificationKey == captured.key }
                }
            }
            val row = withContext(Dispatchers.IO) {
                decisionDao.rowsBetween(injectedWall, System.currentTimeMillis() + 1, 50).first { it.notificationKey == captured.key }
            }
            val decision = ActionDecision(row.notificationKey, ActionDecision.Action.fromString(row.action),
                ActionDecision.Priority.fromString(row.priority), row.reasoning, row.replyText,
                row.confidence, row.snoozeDurationMinutes, row.actionTitle)
            report.put("triage", JSONObject().put("action", decision.action).put("reasoning", decision.reasoning))
            assertTrue("Triage did not admit a follow-up", FollowUp.shouldRun(captured, decision))
            phase("waiting-for-follow-up")
            awaitUntil(timeoutMs = 90_000, message = "No automatic contextual suggestion") {
                ChatStore.messages.value.any { it.role == ChatMessage.Role.ASSISTANT && !it.pending }
            }
            val suggestion = ChatStore.messages.value.last { it.role == ChatMessage.Role.ASSISTANT && !it.pending }
            val consulted = ChatStore.messages.value.filter { it.content.startsWith("[Follow-up]") }.joinToString("\n") { it.content }
            report.put("consulted", consulted).put("suggestion", suggestion.content)
                .put("suggestionActions", JSONArray(suggestion.actions)).put("suggestionMs", SystemClock.elapsedRealtime() - started)
            assertTrue("Follow-up did not consult calendar", consulted.contains("read_calendar"))
            val liveTrace = shell("logcat -d -v brief --pid=${android.os.Process.myPid()} -s MobileClaw-TRACE:I")
            expectedIntervals.take(2).forEach { interval -> assertTrue("Automatic conflict interval missing $interval", liveTrace.contains(interval)) }
            report.put("automaticConflictIntervalsVerified", true)
                .put("automaticCalendarIntervalCount", expectedIntervals.count { liveTrace.contains(it) })
            // The read-only suggestion may offer "Suggest another time" as a chip.
            // The foreground request below verifies the full afternoon before any
            // concrete reply is staged. Record whether the initial bubble gave a slot.
            report.put("automaticSuggestedCompleteSlot", alternativeSlot.containsMatchIn(suggestion.content))
            val explicitConflict = Regex("conflict|overlap|busy|unavailable|occupied", RegexOption.IGNORE_CASE).containsMatchIn(suggestion.content)
            // A factual explanation can name both blocking events without the word "busy".
            val namedConstraints = suggestion.content.contains("Poster review", true) &&
                suggestion.content.contains("Demo setup", true) &&
                Regex("but|until|followed", RegexOption.IGNORE_CASE).containsMatchIn(suggestion.content)
            assertTrue("No conflict explanation", explicitConflict || namedConstraints)
            // source-grounded replies: discard the original triage proposal, then explicitly ask for a fact-based reply.
            PendingAction.resetForDemo()
            dismissOnlyNegativeButtons()
            ui.waitForIdle(500, 3_000)
            screenshot("suggestion")
            holdForRecording("suggestion")
            val proposalCount = proposals.size
            val request = if (shortRequest) "Check availability, then reply with the next free half-hour." else "Read my full afternoon calendar for that day to verify the next free 30-minute slot before drafting a reply. Briefly explain the conflict and propose the verified slot with both start and end times. Use the notification reply action and ask me to confirm before sending. Do not add a calendar event yet."
            report.put("foregroundRequest", request)
            phase("waiting-for-reply-confirmation")
            if (shortRequest && recordingHoldMs > 0) {
                phase("typing-short-request")
                scenario.onActivity { activity ->
                    val input = activity.findViewById<android.widget.EditText>(R.id.chatInput)
                    input.requestFocus()
                    activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        .showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
                delay(500)
                request.forEachIndexed { index, _ ->
                    scenario.onActivity { activity ->
                        val input = activity.findViewById<android.widget.EditText>(R.id.chatInput)
                        input.setText(request.take(index + 1)); input.setSelection(input.length())
                    }
                    delay(65)
                }
                delay(800)
                scenario.onActivity { activity ->
                    val send = activity.findViewById<android.widget.ImageButton>(R.id.btnSend)
                    assertTrue(send.isEnabled); assertTrue(send.performClick())
                }
                delay(600)
                shell("input keyevent KEYCODE_BACK")
                phase("short-request-submitted")
            } else {
                instrumentation.runOnMainSync { vm!!.submit(request) }
            }
            awaitUntil(timeoutMs = 90_000, message = "No reply confirmation proposal") {
                synchronized(proposals) { proposals.drop(proposalCount).any { it.startsWith("Reply to Messaging ·") } }
            }
            val draft = synchronized(proposals) { proposals.drop(proposalCount).last { it.startsWith("Reply to Messaging ·") } }
            assertTrue("Wrong proposed slot; never approve this draft", alternativeSlot.containsMatchIn(draft))
            report.put("replyConfirmation", draft)
            awaitUntil(timeoutMs = 5_000, message = "Reply proposal exists but confirmation dialog is not visible") {
                var visible = false
                instrumentation.runOnMainSync {
                    visible = WindowInspector.getGlobalWindowViews().any { root ->
                        root.findViewById<android.widget.TextView>(android.R.id.message)?.let {
                            it.isShown && alternativeSlot.containsMatchIn(it.text.toString())
                        } == true
                    }
                }
                visible
            }
            report.put("confirmationDialogVisible", true)
            ui.waitForIdle(500, 3_000)
            screenshot("confirmation")
            holdForRecording("confirmation")
            val finalTrace = shell("logcat -d -v brief --pid=${android.os.Process.myPid()} -s MobileClaw-TRACE:I")
            val foregroundTrace = finalTrace.substringAfter(request, "")
            assertTrue("Foreground must actually re-read the calendar", Regex("TOOL CALL  #\\d+  read_calendar ").containsMatchIn(foregroundTrace))
            val foregroundCalendar = foregroundTrace
            expectedIntervals.forEach { interval -> assertTrue("Foreground calendar verification missing $interval", foregroundCalendar.contains(interval)) }
            report.put("finalCalendarIntervalsVerified", true)
            assertTrue("Final draft must propose 15:00–15:30", alternativeSlot.containsMatchIn(draft))
            if (!shortRequest) {
                assertTrue("Draft must explain the conflict", Regex("conflict|busy|unavailable|meeting|review|setup", RegexOption.IGNORE_CASE).containsMatchIn(draft))
            }
            report.put("status", "passed")
        } catch (t: Throwable) {
            report.put("status", "failed").put("error", "${t.javaClass.simpleName}: ${t.message}")
            screenshot("failure")
            throw t
        } finally {
            PendingAction.beginDemoReset()
            var drained = false
            try {
                ui.dropShellPermissionIdentity()
                instrumentation.runOnMainSync { AgentRunState.setPaused(true); vm?.cancel() }
                withContext(Dispatchers.Main) {
                    val jobs = vm?.viewModelScope?.coroutineContext?.get(Job)?.children?.toList().orEmpty()
                    jobs.forEach { it.cancel() }
                    jobs.forEach { it.join() }
                    NotifListener.instance?.beginDemoReset()
                    NotifListener.instance?.drainDemoRuns()
                    NotifListener.instance?.finishDemoReset()
                }
                drained = true
                dismissOnlyNegativeButtons()
            } catch (cleanup: Throwable) {
                report.put("cleanupError", "${cleanup.javaClass.simpleName}: ${cleanup.message}").put("status", "failed")
            } finally {
                scope.cancel()
                if (registered) runCatching { context.unregisterReceiver(receiver) }
                val edit = prefs.edit()
                saved.forEach { (key, value) ->
                    if (key == AppSettings.KEY_FULL_AUTOMATION && !drained) edit.putBoolean(key, false)
                    else if (value == null) edit.remove(key) else edit.putBoolean(key, value)
                }
                edit.commit()
                if (drained) {
                    PendingAction.finishDemoReset()
                    instrumentation.runOnMainSync {
                        AgentRunState.setPaused(oldPaused)
                        if (!oldPaused) NotifListener.instance?.onRunStateResumed()
                    }
                }
                // A failed drain leaves automation OFF and the action gate blocked.
                // Restart the app before another rehearsal; do not silently reopen it.
                report.put("cleanupDrained", drained)
            }
            report.put("proposals", JSONArray(proposals.toList())).put("elapsedMs", SystemClock.elapsedRealtime() - started)
                .put("pausedStateRestored", AgentRunState.isPaused == oldPaused)
                .put("settingsRestored", keys.all { if (saved[it] == null) !prefs.contains(it) else prefs.getBoolean(it, false) == saved[it] })
            report.put("chat", JSONArray(ChatStore.messages.value.map { JSONObject().put("role", it.role).put("content", it.content) }))
            val audit = MobileClawDb.get(context).actionLog().rowsBetween(startedWall, System.currentTimeMillis() + 1, 500)
            report.put("actionAudit", JSONArray(audit.map { JSONObject().put("appName", it.appName).put("action", it.actionTitle).put("success", it.success) }))
            report.put("successfulOutboundAuditRows", audit.count { it.success && (it.appName == "send_sms" || it.actionTitle.contains("reply", true)) })
            report.put("successfulCalendarWriteAuditRows", audit.count { it.success && it.appName == "create_event" })
            File(output, "report.json").writeText(report.toString(2) + "\n")
            scenario?.close()
            android.util.Log.i("MobileClaw-SCHEDULE", "END $runId ${report.optString("status")}")
            assertFalse("Cleanup failed; inspect report and restart app", report.has("cleanupError"))
            assertEquals("An outbound action unexpectedly succeeded", 0, report.getInt("successfulOutboundAuditRows"))
            assertEquals("Calendar unexpectedly changed", 0, report.getInt("successfulCalendarWriteAuditRows"))
        }
    }
}
