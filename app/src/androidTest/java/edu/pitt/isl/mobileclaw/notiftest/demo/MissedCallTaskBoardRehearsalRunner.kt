package edu.pitt.isl.mobileclaw.notiftest.demo

import android.Manifest
import android.app.Notification
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.ContactsContract
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
import edu.pitt.isl.mobileclaw.notiftest.skills.calendar.ReadCalendarTool
import edu.pitt.isl.mobileclaw.notiftest.skills.messaging.LookupContactTool
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A real Dialer missed-call notification, isolated fixture context and one confirmed local write. */
@RunWith(AndroidJUnit4::class)
class MissedCallTaskBoardRehearsalRunner {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ui get() = ins.uiAutomation
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val board = "edu.pitt.isl.mobileclaw.taskboard"
    private val person = "Morgan Demo"
    private val number = "+12025550145"
    private fun shell(command: String) = ui.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
    }
    private fun tasks() = JSONObject(shell("cmd app_function execute-app-function --package $board --function $board.TaskBoardFunctions#listTasks --parameters {}"))
        .getJSONArray("androidAppfunctionsReturnValue")
    private fun text(task: JSONObject, key: String) = task.getJSONArray(key).getString(0)

    /** Fixture writes are local setup, never agent actions. Only owned rows may be refreshed. */
    private fun prepareContext(): JSONObject {
        ui.adoptShellPermissionIdentity(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        try {
            val cr = context.contentResolver
            val demoCalendar = DemoCalendar(context)
            val id = demoCalendar.ensure()
            val minute = 60_000L
            val anchor = (System.currentTimeMillis() / minute + 1) * minute
            val finish = anchor + 20 * minute
            val events = demoCalendar.refreshScenario(id, "callback", listOf(
                DemoCalendar.Fixture("meeting", "MobileClaw demo meeting", anchor - 10 * minute, finish),
                DemoCalendar.Fixture("review", "MobileClaw demo review", finish + 30 * minute, finish + 60 * minute)
            ))
            val meeting = events[0].id
            val review = events[1].id
            val contacts = mutableListOf<Long>()
            cr.query(ContactsContract.Data.CONTENT_URI, arrayOf("raw_contact_id"),
                "mimetype=? AND data1=?", arrayOf(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, person), null)!!.use {
                while (it.moveToNext()) contacts += it.getLong(0)
            }
            // Refuse ambiguity instead of modifying an existing person's record.
            check(contacts.size <= 1)
            val contact = contacts.singleOrNull() ?: ContentUris.parseId(cr.insert(ContactsContract.RawContacts.CONTENT_URI,
                ContentValues().apply { putNull("account_name"); putNull("account_type") })!!).also { raw ->
                fun data(mime: String, value: String) { cr.insert(ContactsContract.Data.CONTENT_URI, ContentValues().apply {
                    put("raw_contact_id", raw); put("mimetype", mime); put("data1", value)
                })!! }
                data(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, person)
                data(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, number)
            }
            val callbackSlot = demoCalendar.firstFreeSlot(id, finish)
            val phoneRows = mutableListOf<String>()
            cr.query(ContactsContract.Data.CONTENT_URI, arrayOf("data1"), "raw_contact_id=? AND mimetype=?",
                arrayOf(contact.toString(), ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE), null)!!.use {
                while (it.moveToNext()) phoneRows += it.getString(0)
            }
            assertEquals("Existing fixture contact must be exactly the reserved demo number", listOf(number), phoneRows)
            return JSONObject().put("calendarId", id).put("contactRawId", contact).put("caller", person)
                .put("number", number).put("meetingEventId", meeting).put("reviewEventId", review)
                .put("meetingEndMs", finish).put("callbackStartMs", callbackSlot.startMs).put("callbackEndMs", callbackSlot.endMs)
                .put("slotRule", "first complete 15-minute gap across all shared-calendar instances after the callback meeting")
                .put("timezone", TimeZone.getDefault().id)
        } finally { ui.dropShellPermissionIdentity() }
    }

    @Test fun prepareLocalFixtures() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("callbackPrepare") == "true")
        check(AgentRunState.isPaused)
        val fixture = prepareContext()
        File(context.filesDir, "demo-callback/fixture.json").apply { parentFile!!.mkdirs(); writeText(fixture.toString(2)) }
    }

    @Test fun capturedMissedCallToConfirmedTask() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("callbackRehearsal") == "true")
        val live = args.getString("callbackLive") == "true"
        val proposalOnly = args.getString("callbackProposalOnly") == "true"
        require(!proposalOnly || live)
        val recordingHoldMs = args.getString("callbackRecordingHoldMs")?.toLong() ?: 0L
        require(recordingHoldMs in 0L..8_000L)
        val recordingHome = args.getString("callbackRecordingHome") == "true"
        require(!recordingHome || recordingHoldMs > 0)
        val additionalDemoTask = args.getString("callbackAdditionalDemoTask") == "true"
        require(!additionalDemoTask || (live && !proposalOnly && recordingHoldMs > 0))
        val runId = requireNotNull(args.getString("callbackRunId")).also { require(it.matches(Regex("CALLBACK-[A-Z0-9-]{1,40}"))) }
        val out = File(context.filesDir, "demo-callback/$runId").apply { mkdirs() }
        val report = JSONObject().put("runId", runId).put("liveLlm", false).put("wroteTask", false).put("proposalOnly", proposalOnly)
            .put("source", "actual com.android.dialer missed-call notification; managed test call, not carrier delivery")
            .put("autonomousBackgroundTriage", false)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val boolKeys = listOf(AppSettings.KEY_FULL_AUTOMATION, AppSettings.KEY_REASONING_TRACE, AppSettings.KEY_FOLLOW_UP)
        val saved = boolKeys.associateWith { if (prefs.contains(it)) prefs.getBoolean(it, false) else null }
        val oldCalendar = prefs.getString(AppSettings.KEY_CALENDAR_SOURCE, null)
        val oldPaused = AgentRunState.isPaused
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        val proposals = java.util.Collections.synchronizedList(mutableListOf<PendingAction.Proposal>())
        var activity: ActivityScenario<MainActivity>? = null
        var vm: MainViewModel? = null
        val started = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val phaseTimings = JSONArray()
        report.put("startedWallMs", wall).put("phaseTimings", phaseTimings)
            .put("recordingHoldMs", recordingHoldMs).put("additionalDemoTask", additionalDemoTask)
        fun phase(name: String) {
            phaseTimings.put(JSONObject().put("name", name).put("elapsedMs", SystemClock.elapsedRealtime() - started))
            report.put("phase", name)
            File(out, "report.json").writeText(report.toString(2) + "\n")
            android.util.Log.i("MobileClaw-CALLBACK", "$runId $name")
        }
        suspend fun holdForRecording(name: String) {
            phase("$name-visible"); delay(recordingHoldMs); phase("$name-hold-finished")
        }
        fun shot(name: String) { ui.takeScreenshot()?.let { bitmap ->
            File(out, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        } }
        try {
            assertTrue("Start paused", oldPaused)
            assertFalse("Fresh process required; do not reset before source capture", DemoReset.hasReset)
            // Fail before simulating a call when the existing privileged bridge is unavailable.
            awaitUntil(timeoutMs = 15_000, message = "Start the installed Shizuku server before recording") {
                ShizukuBridge.isShizukuRunning()
            }
            var permissionRequested = false
            ins.runOnMainSync { permissionRequested = ShizukuBridge.requestPermission() }
            assertTrue("Shizuku permission request could not start", permissionRequested)
            awaitUntil(timeoutMs = 15_000, message = "Shizuku user service is not connected") { ShellRegistry.isConnected }
            val fixture = prepareContext()
            report.put("fixture", fixture)
            var simulatedKey: String? = null
            if (args.getString("callbackSimulate") == "true") {
                shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
                if (recordingHoldMs > 0) shell("am start -W -n ${context.packageName}/.MainActivity")
                if (recordingHome) {
                    shell("input keyevent KEYCODE_HOME")
                    phase("home-before-incoming")
                    delay(2_000)
                }
                phase("incoming-call")
                simulatedKey = ManagedIncomingCallFixture.injectAndObserve("MCCALL-${runId.removePrefix("CALLBACK-")}", dismissPreviousFixture = recordingHome).key
                report.put("simulatedViaTelecom", true).put("simulatedNotificationKey", simulatedKey)
            }
            ui.grantRuntimePermission(context.packageName, Manifest.permission.READ_CONTACTS)
            ui.grantRuntimePermission(context.packageName, Manifest.permission.READ_CALENDAR)
            prefs.edit().putBoolean(AppSettings.KEY_FULL_AUTOMATION, false).putBoolean(AppSettings.KEY_REASONING_TRACE, true)
                .putBoolean(AppSettings.KEY_FOLLOW_UP, false).putString(AppSettings.KEY_CALENDAR_SOURCE, fixture.getLong("calendarId").toString()).commit()

            val before = tasks()
            report.put("tasksBefore", before)
            if (live && !proposalOnly) {
                if (!additionalDemoTask) {
                    assertFalse("Callback already added; preserve existing board rather than duplicate", before.toString().contains("Morgan Demo", true))
                } else {
                    // Recording a new dated fixture may add one task, never reset or overwrite the old board.
                    val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(fixture.getLong("callbackStartMs")))
                    val time = SimpleDateFormat("HH:mm", Locale.US).format(Date(fixture.getLong("callbackStartMs")))
                    for (i in 0 until before.length()) {
                        val task = before.getJSONObject(i)
                        if (text(task, "title").contains("Morgan", true)) {
                            val note = text(task, "note")
                            assertTrue("Existing Morgan task must be the reserved fixture", note.contains(number) && note.contains("missed", true))
                            assertFalse("Never repeat the same callback slot", note.contains(date) && note.contains(time))
                        }
                    }
                }
            }
            shell("cmd notification allow_listener ${context.packageName}/${NotifListener::class.java.name}")
            ins.runOnMainSync { NotificationListenerService.requestRebind(ComponentName(context, NotifListener::class.java)) }
            awaitUntil(timeoutMs = 15_000) { NotifListener.instance != null && ChatStore.sessions.value.isNotEmpty() }
            withContext(Dispatchers.Main) {
                val session = ChatStore.newSession(); ChatStore.rename(session, "Missed call to TaskBoard $runId")
                report.put("sessionId", session)
                NotifStore.clear(); AgentRunState.setPaused(false)
                try { NotifListener.instance!!.onRunStateResumed() } finally { AgentRunState.setPaused(true) }
                val targets = NotifListener.instance!!.activeNotifications.orEmpty().filter { n ->
                    n.packageName == "com.android.dialer" && n.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 &&
                        (simulatedKey == null || n.key == simulatedKey) &&
                        listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT).any {
                            n.notification.extras.getCharSequence(it)?.contains(person) == true
                        }
                }
                assertEquals("Expected one individual native Dialer notification for Morgan Demo", 1, targets.size)
                val source = targets.single()
                val event = NotifStore.events.value.single { it.posted && it.key == source.key }
                assertTrue("Not a missed-call source", "${event.title} ${event.text}".contains("missed", true))
                assertTrue("Source too old", System.currentTimeMillis() - source.postTime < 30 * 60_000)
                NotifStore.clear(); NotifStore.add(event)
                report.put("notification", event.toJSONObject()).put("testInputSelection", true)
            }
            if (recordingHoldMs > 0) {
                if (recordingHome) {
                    phase("home-after-missed-call")
                    delay(3_000)
                }
                // Show the actual native missed-call notification, without pressing its actions.
                shell("cmd statusbar expand-notifications")
                ui.waitForIdle(500, 3_000)
                holdForRecording("missed-notification")
                shell("cmd statusbar collapse")
            }
            val contactText = LookupContactTool(context).invoke(JSONObject().put("query", person))
            assertTrue(contactText.contains(person)); assertTrue(contactText.contains(number))
            report.put("contactReader", contactText)
            val calendarText = ReadCalendarTool(context).invoke(JSONObject())
            assertTrue(calendarText.contains("MobileClaw demo meeting")); assertTrue(calendarText.contains("MobileClaw demo review"))
            report.put("calendarReader", calendarText).put("captureMs", SystemClock.elapsedRealtime() - started)
            shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
            activity = ActivityScenario.launch(MainActivity::class.java)
            activity.onActivity { vm = ViewModelProvider(it)[MainViewModel::class.java] }
            if (!live) {
                assertEquals(before.toString(), tasks().toString())
                report.put("status", "passed").put("tasksAfter", before).put("phase", "offline-source-and-readers-verified")
                shot("capture"); return@runBlocking
            }
            // This live rehearsal is authorized for fictional fixtures only. Refuse
            // the normal foreground memory injection if it would add personal facts.
            assertNull("Personal memory must not accompany the fictional live rehearsal",
                MemoryInjection.buildBlock(MemoryStore.memories.value))
            assertTrue(ChatStore.messages.value.isEmpty())
            assertEquals(1, NotifStore.events.value.size)
            assertEquals(fixture.getLong("calendarId").toString(), AppSettings.calendarReadSource)
            report.put("personalMemoryInjected", false).put("fictionalInputsOnly", true)
            scope.launch { PendingAction.proposals.collect { proposals.add(it) } }
            val request = "Read the latest demo missed-call notification, look up that caller in contacts and check my calendar. " +
                "Add one callback task to TaskBoard for the first free 15-minute slot after my current meeting. " +
                "Include the caller's number, the slot date in YYYY-MM-DD, BOTH its start AND end in 24-hour HH:mm format, the IANA timezone, and the missed call as source in the note. " +
                "Ask me to confirm before adding. Do not call or send a message."
            report.put("request", request).put("liveLlm", true)
            phase("user-request")
            ins.runOnMainSync { vm!!.submit(request) }
            awaitUntil(timeoutMs = 100_000, message = "No validated callback proposal") {
                synchronized(proposals) { proposals.any { it.description.startsWith("Call app function in $board:") && it.description.contains("addTask") } } || !vm!!.running.value
            }
            awaitUntil(timeoutMs = 20_000) { !vm!!.running.value }
            val pending = synchronized(proposals) { proposals.toList() }
            assertEquals("Exactly one modifying proposal", 1, pending.size)
            val proposal = pending.single()
            assertTrue(proposal.description.startsWith("Call app function in $board:\naddTask "))
            assertFalse(proposal.allowFullAutomation)
            val params = JSONObject(proposal.description.substringAfter("addTask "))
            val title = params.getString("title"); val note = params.getString("note")
            assertTrue(title.contains("Morgan", true)); assertTrue(title.contains("call", true))
            assertTrue(note.contains("missed", true)); assertTrue(note.replace(Regex("[^0-9]"), "").contains("12025550145"))
            val fmt = SimpleDateFormat("HH:mm", Locale.US)
            assertTrue("Missing exact callback date", note.contains(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(fixture.getLong("callbackStartMs")))))
            assertTrue("Missing callback timezone", note.contains(TimeZone.getDefault().id))
            assertTrue("Expected first free callback slot in note: $note", note.contains(fmt.format(Date(fixture.getLong("callbackStartMs")))))
            assertTrue("Expected 15-minute end in note: $note", note.contains(fmt.format(Date(fixture.getLong("callbackEndMs")))))
            val traceBefore = shell("logcat -d -v brief --pid=${android.os.Process.myPid()} -s MobileClaw-TRACE:I")
            assertTrue("Model must actually look up caller", Regex("TOOL CALL  #\\d+  lookup_contact ").containsMatchIn(traceBefore))
            assertTrue("Model must actually read calendar", Regex("TOOL CALL  #\\d+  read_calendar ").containsMatchIn(traceBefore))
            report.put("proposal", proposal.description).put("confirmationMs", SystemClock.elapsedRealtime() - started)
            assertEquals("Board changed before approval", before.toString(), tasks().toString())
            val assistant = ChatStore.messages.value.last { it.role == ChatMessage.Role.ASSISTANT }
            assertTrue("Only application-owned pending status may be shown", assistant.content.startsWith("Waiting for confirmation."))
            assertFalse(assistant.content.contains("I've added", true))
            report.put("pendingText", assistant.content)
            ui.waitForIdle(500, 3000); shot("confirmation")
            holdForRecording("confirmation")
            if (proposalOnly) {
                report.put("status", "passed").put("tasksAfter", before).put("existingTasksPreserved", true)
                    .put("phase", "verified-pending-without-approval")
                return@runBlocking
            }
            phase("user-approval")
            ins.runOnMainSync {
                val root = WindowInspector.getGlobalWindowViews().single { it.findViewById<TextView>(android.R.id.message)?.let { m -> m.isShown && m.text.toString() == proposal.description } == true }
                val button = root.findViewById<Button>(android.R.id.button1)
                assertEquals("Do it", button.text.toString()); assertTrue(button.performClick())
            }
            report.put("approvedViaVisibleDialog", true)
            awaitUntil(timeoutMs = 30_000) { ChatStore.messages.value.any { it.role == ChatMessage.Role.SYSTEM && it.content.contains("addTask returned:") } }
            val after = tasks()
            assertEquals(before.length() + 1, after.length())
            for (i in 0 until before.length()) assertEquals(before.getJSONObject(i).toString(), after.getJSONObject(i).toString())
            val added = after.getJSONObject(after.length() - 1)
            assertEquals(title, text(added, "title")); assertEquals(note, text(added, "note"))
            assertFalse(added.getJSONArray("done").getBoolean(0))
            report.put("tasksAfter", after).put("addedTask", added).put("wroteTask", true).put("existingTasksPreserved", true)
                .put("resultMs", SystemClock.elapsedRealtime() - started).put("status", "passed")
            shot("result")
            holdForRecording("result")
            if (recordingHoldMs > 0) {
                shell("am start -W -n $board/.MainActivity")
                ui.waitForIdle(500, 3_000)
                shot("taskboard")
                holdForRecording("taskboard")
            }
        } catch (t: Throwable) { report.put("status", "failed").put("error", "${t.javaClass.simpleName}: ${t.message}"); shot("failure"); throw t }
        finally {
            PendingAction.beginDemoReset()
            ins.runOnMainSync { AgentRunState.setPaused(true); vm?.cancel() }
            withContext(Dispatchers.Main) {
                vm?.viewModelScope?.coroutineContext?.get(Job)?.children?.toList().orEmpty().let { jobs -> jobs.forEach { it.cancel() }; jobs.forEach { it.join() } }
                NotifListener.instance?.beginDemoReset(); NotifListener.instance?.drainDemoRuns(); NotifListener.instance?.finishDemoReset()
            }
            ins.runOnMainSync { WindowInspector.getGlobalWindowViews().forEach { root ->
                root.findViewById<Button>(android.R.id.button2)?.takeIf { it.text.toString() == "Not now" }?.performClick()
            } }
            scope.cancel()
            prefs.edit().apply {
                saved.forEach { (k, v) -> if (v == null) remove(k) else putBoolean(k, v) }
                if (oldCalendar == null) remove(AppSettings.KEY_CALENDAR_SOURCE) else putString(AppSettings.KEY_CALENDAR_SOURCE, oldCalendar)
            }.commit()
            PendingAction.finishDemoReset()
            assertTrue("Original state must be paused", oldPaused)
            report.put("pausedStateRestored", AgentRunState.isPaused == oldPaused)
                .put("calendarSelectionRestored", prefs.getString(AppSettings.KEY_CALENDAR_SOURCE, null) == oldCalendar)
                .put("chat", JSONArray(ChatStore.messages.value.map { JSONObject().put("role", it.role).put("content", it.content) }))
            val audit = MobileClawDb.get(context).actionLog().rowsBetween(wall, System.currentTimeMillis() + 1, 500)
            val outbound = audit.count { it.success && (it.appName == "send_sms" || it.actionTitle.contains("reply", true)) }
            val writes = audit.count { it.success && it.appName == "create_event" }
            report.put("audit", JSONArray(audit.map { JSONObject().put("source", it.appName).put("action", it.actionTitle).put("success", it.success) }))
                .put("successfulOutboundAuditRows", outbound).put("successfulCalendarWriteAuditRows", writes)
                .put("elapsedMs", SystemClock.elapsedRealtime() - started)
            File(out, "report.json").writeText(report.toString(2) + "\n")
            File(out, "trace.log").writeText(shell("logcat -d -v brief --pid=${android.os.Process.myPid()} -s MobileClaw-TRACE:I") + "\n")
            activity?.close()
            assertEquals(0, outbound); assertEquals(0, writes)
        }
    }
}
