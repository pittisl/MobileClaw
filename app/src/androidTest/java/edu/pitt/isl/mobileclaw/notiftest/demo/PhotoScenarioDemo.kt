package edu.pitt.isl.mobileclaw.notiftest.demo

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.ContactsContract
import android.view.accessibility.AccessibilityNodeInfo
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
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections

/**
 * Opt-in live-model acceptance of the photo scenario. It opens the real preview,
 * selects the photo there, and stops at the app's image confirmation. No approval,
 * send API, native composer, or outbound-message button is called by this runner.
 *
 * Run only on the isolated demo phone with the five EXIF fixtures already indexed.
 * The two controls (a previous-day dog and a same-day cat) must not become candidates.
 * This checks preparation and confirmation; it does not claim carrier delivery.
 */
@RunWith(AndroidJUnit4::class)
class PhotoScenarioDemo {
    @Test fun twoShortRequestsSelectPhotoAndStageMms() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with -e photoScenario true", args.getString("photoScenario") == "true")
        val runId = requireNotNull(args.getString("photoRunId"))
        require(runId.matches(Regex("PHOTO-[A-Z0-9-]{1,40}")))
        val fixtureDirectory = File(requireNotNull(args.getString("photoFixtureDirectory"))).canonicalFile
        require(fixtureDirectory.path.startsWith("/storage/emulated/0/Pictures/MobileClawPhotoDemo/"))
        val expectedDay = LocalDate.parse(args.getString("photoDay") ?: "2026-10-14")
        val holdMs = (args.getString("photoRecordingHoldMs") ?: "0").toLong()
        require(holdMs in 0L..8_000L)
        val cameraDay = expectedDay.format(DateTimeFormatter.BASIC_ISO_DATE)
        val previousDay = expectedDay.minusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE)
        val expectedNames = setOf("IMG_${cameraDay}_091500.jpg", "IMG_${cameraDay}_123000.jpg", "IMG_${cameraDay}_174500.jpg")
        val controls = setOf("IMG_${previousDay}_102000.jpg", "IMG_${cameraDay}_141000.jpg")
        val selectedName = "IMG_${cameraDay}_091500.jpg"
        val firstRequest = "find me my photo about the dog yesterday"
        val secondRequest = "send that pic to Amy's phone"
        val recipient = "+12025550198" // NANP-reserved fictional number; never sent to.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ui = instrumentation.uiAutomation
        val output = File(context.filesDir, "demo-photo-scenario/$runId").apply { mkdirs() }
        val report = JSONObject().put("runId", runId).put("liveLlm", true).put("model", LlmConfig.model)
            .put("firstRequest", firstRequest).put("secondRequest", secondRequest)
            .put("recipient", recipient).put("noApprovalPerformed", true).put("scope", "Stops before sending")
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val hadTrace = prefs.contains(AppSettings.KEY_REASONING_TRACE)
        val oldTrace = prefs.getBoolean(AppSettings.KEY_REASONING_TRACE, false)
        val oldSession = ChatStore.activeSessionId.value
        val proposals = Collections.synchronizedList(mutableListOf<PendingAction.Proposal>())
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        val started = SystemClock.elapsedRealtime()
        val startedWall = System.currentTimeMillis()
        val traceMarker = "PHOTO_BEGIN $runId $startedWall"
        val phases = JSONArray()
        report.put("startedWallMs", startedWall).put("phases", phases)
        var ownedContactId: Long? = null
        var sessionId: String? = null
        var scenario: ActivityScenario<MainActivity>? = null
        var vm: MainViewModel? = null
        var smsBefore: List<Long>? = null
        var mmsBefore: List<Long>? = null
        fun shell(command: String): String = ui.executeShellCommand(command).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
        }
        fun phase(name: String) {
            phases.put(JSONObject().put("name", name).put("elapsedMs", SystemClock.elapsedRealtime() - started))
            report.put("phase", name)
            output.resolve("report.json").writeText(report.toString(2) + "\n")
        }
        fun captureTrace(): String {
            val logged = shell("logcat -d -v brief --pid=${android.os.Process.myPid()} -s ${ReasoningTrace.TAG}:I")
            val markerAt = logged.lastIndexOf(traceMarker)
            val trace = if (markerAt < 0) "" else logged.substring(markerAt + traceMarker.length)
            output.resolve("trace.txt").writeText(trace)
            report.put("traceCaptured", true).put("traceMarkerFound", markerAt >= 0)
                .put("traceCharacters", trace.length)
            return trace
        }
        fun nodes(node: AccessibilityNodeInfo? = ui.rootInActiveWindow): List<AccessibilityNodeInfo> {
            if (node == null) return emptyList()
            return listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
        }
        fun clickOnly(label: String, description: Boolean = false) {
            var clickable: AccessibilityNodeInfo? = null
            awaitUntil(timeoutMs = 10_000, message = "Missing enabled clickable UI target: $label") {
                var target = nodes().firstOrNull { it.isVisibleToUser && it.isEnabled &&
                    (if (description) it.contentDescription else it.text)?.toString() == label }
                while (target != null && !target.isClickable && target.parent != null) target = target.parent
                clickable = target?.takeIf { it.isClickable && it.isVisibleToUser && it.isEnabled }
                clickable != null
            }
            assertTrue("Click failed: $label", clickable!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        fun screenshot(name: String) {
            ui.waitForIdle(500, 5_000)
            val tree = JSONArray(nodes().map { n -> JSONObject().put("id", n.viewIdResourceName)
                .put("class", n.className?.toString()).put("text", n.text?.toString())
                .put("description", n.contentDescription?.toString()).put("visible", n.isVisibleToUser)
                .put("enabled", n.isEnabled) })
            output.resolve("$name-ui.json").writeText(tree.toString(2) + "\n")
            ui.takeScreenshot()?.let { bitmap ->
                output.resolve("$name.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
        fun outgoing(uri: String, selection: String): List<Long> = context.contentResolver.query(
            Uri.parse(uri), arrayOf("_id"), selection, null, "_id ASC")!!.use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }
        fun checkOutboundUnchanged() {
            if (smsBefore == null || mmsBefore == null) return
            ui.adoptShellPermissionIdentity(Manifest.permission.READ_SMS)
            try {
                assertEquals("Outgoing SMS rows changed", smsBefore, outgoing("content://sms", "type IN (2,4,5,6)"))
                assertEquals("Outgoing MMS rows changed", mmsBefore, outgoing("content://mms", "msg_box IN (2,4)"))
                report.put("outboundProviderRowsUnchanged", true)
            } finally { ui.dropShellPermissionIdentity() }
        }
        fun verifyDraftCleanup() {
            val paths = synchronized(proposals) { proposals.mapNotNull { it.imagePreviewPath }.distinct() }
            val drafts = paths.map { path ->
                val image = File(path)
                val directory = requireNotNull(image.parentFile)
                JSONObject().put("imagePreviewPath", image.path).put("draftDirectory", directory.path)
                    .put("imageExists", image.exists()).put("directoryExists", directory.exists())
            }
            val removed = drafts.all { !it.getBoolean("imageExists") && !it.getBoolean("directoryExists") }
            report.put("draftCleanup", JSONArray(drafts)).put("draftsChecked", drafts.size)
                .put("draftCleanupVerified", removed)
            assertTrue("An unapproved photo draft or its directory survived cleanup", removed)
        }
        suspend fun hold() { if (holdMs > 0) delay(holdMs) }
        android.util.Log.i(ReasoningTrace.TAG, traceMarker)
        try {
            AppSettings.init(context)
            assertTrue("Use the already-paused demo phone", AgentRunState.isPaused)
            assertFalse("Full Automation must already be off", AppSettings.fullAutomation)
            assertTrue("No model configured", LlmConfig.isConfigured)
            assertTrue("Grant app contacts access before the rehearsal", Capability.READ_CONTACTS.isGranted(context))
            assertEquals("Regenerate fixtures if the device's yesterday has changed", expectedDay, LocalDate.now().minusDays(1))
            assertEquals("Use only the five isolated EXIF fixtures", expectedNames + controls,
                fixtureDirectory.listFiles()!!.filter { it.extension.lowercase() == "jpg" }.map { it.name }.toSet())
            ui.adoptShellPermissionIdentity(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS,
                Manifest.permission.READ_SMS)
            try {
                val cr = context.contentResolver
                // A partial-name model lookup must never see a real Amy during this demo.
                for (uri in listOf(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    ContactsContract.CommonDataKinds.Email.CONTENT_URI)) {
                    cr.query(uri, arrayOf("_id"), "display_name LIKE ?", arrayOf("%Amy%"), null)!!.use {
                        assertEquals("Existing Amy contact; do not expose it to the demo", 0, it.count)
                    }
                }
                val raw = ContentUris.parseId(cr.insert(ContactsContract.RawContacts.CONTENT_URI, ContentValues().apply {
                    putNull(ContactsContract.RawContacts.ACCOUNT_NAME)
                    putNull(ContactsContract.RawContacts.ACCOUNT_TYPE)
                    put(ContactsContract.RawContacts.AGGREGATION_MODE, ContactsContract.RawContacts.AGGREGATION_MODE_DISABLED)
                })!!)
                ownedContactId = raw
                fun insertData(mime: String, value: String) = cr.insert(ContactsContract.Data.CONTENT_URI, ContentValues().apply {
                    put(ContactsContract.Data.RAW_CONTACT_ID, raw)
                    put(ContactsContract.Data.MIMETYPE, mime)
                    put(ContactsContract.Data.DATA1, value)
                })!!
                insertData(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, "Amy Demo")
                insertData(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, recipient)
                report.put("ownedContactRawId", raw).put("contactName", "Amy Demo")
                smsBefore = outgoing("content://sms", "type IN (2,4,5,6)")
                mmsBefore = outgoing("content://mms", "msg_box IN (2,4)")
            } finally { ui.dropShellPermissionIdentity() }
            prefs.edit().putBoolean(AppSettings.KEY_REASONING_TRACE, true).commit()
            awaitUntil { ChatStore.sessions.value.isNotEmpty() }
            withContext(Dispatchers.Main) {
                sessionId = ChatStore.newSession()
                ChatStore.rename(sessionId!!, "Photo rehearsal $runId")
                DemoReset.run(sessionId!!) {}
            }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { vm = ViewModelProvider(it)[MainViewModel::class.java] }
            scope.launch(start = CoroutineStart.UNDISPATCHED) { PendingAction.proposals.collect { proposals.add(it) } }
            phase("search-request")
            withContext(Dispatchers.Main) { vm!!.submit(firstRequest) }
            awaitUntil(timeoutMs = 120_000, message = "Photo search did not settle") { !vm!!.running.value }
            val candidates = ChatStore.messages.value.flatMap { it.photos }
            report.put("candidates", JSONArray(candidates.map { JSONObject().put("name", it.name)
                .put("path", it.path).put("capturedAtMs", it.capturedAtMs) }))
            assertEquals("Candidates must be exactly yesterday's three dogs", expectedNames, candidates.map { it.name }.toSet())
            assertEquals("Duplicate photo cards", expectedNames.size, candidates.size)
            assertTrue("Candidate escaped the isolated fixture directory", candidates.all { File(it.path).canonicalFile.parentFile == fixtureDirectory })
            assertTrue("Candidate capture dates must come from yesterday's metadata", candidates.all {
                it.capturedAtMs?.let { millis -> Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate() } == expectedDay
            })
            assertTrue("A send proposal must not appear during search", proposals.isEmpty())
            phase("candidates-visible")
            screenshot("candidates")
            hold()
            clickOnly("Preview photo: $selectedName", description = true)
            awaitUntil(timeoutMs = 10_000, message = "Photo preview has not finished decoding") {
                val visible = nodes().filter { it.isVisibleToUser }
                visible.any { it.text?.toString() == "Preview photo" } &&
                    visible.any { it.text?.toString() == "Use this photo" && it.isEnabled && it.isClickable }
            }
            phase("preview-visible")
            screenshot("preview")
            hold()
            clickOnly("Use this photo")
            awaitUntil(message = "Preview choice did not become the selected photo") {
                PhotoSelectionStore.selected(sessionId!!)?.name == selectedName
            }
            val selected = PhotoSelectionStore.selected(sessionId!!)!!
            val selectedFile = File(fixtureDirectory, selectedName)
            assertEquals(selectedFile.canonicalPath, File(selected.path).canonicalPath)
            assertEquals(MessageDigest.getInstance("SHA-256").digest(selectedFile.readBytes())
                .joinToString("") { "%02x".format(it) }, selected.sha256)
            report.put("selectedPhoto", JSONObject().put("name", selected.name).put("path", selected.path)
                .put("sha256", selected.sha256)).put("selectedThroughRealPreview", true)
            phase("share-request")
            withContext(Dispatchers.Main) { vm!!.submit(secondRequest) }
            awaitUntil(timeoutMs = 120_000, message = "No image-send confirmation proposed") {
                synchronized(proposals) { proposals.any { it.imagePreviewPath != null } }
            }
            val proposal = synchronized(proposals) { proposals.single() }
            assertFalse("Photo sending must always ask", proposal.allowFullAutomation)
            assertEquals("Proposal crossed chat sessions", sessionId, proposal.sessionId)
            assertTrue("Wrong recipient in confirmation", proposal.description.filter(Char::isDigit).contains("12025550198"))
            assertTrue("Confirmation lost Amy's identity", proposal.description.contains("Amy", ignoreCase = true))
            assertTrue("Prepared JPEG missing", File(proposal.imagePreviewPath!!).isFile)
            // The photo tool resolves the name against Contacts itself. Verify the
            // actual prepared record against this run's provider object instead of
            // demanding a redundant separate lookup_contact model round-trip.
            val rawContactId = requireNotNull(ownedContactId)
            val contactId = context.contentResolver.query(
                ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawContactId),
                arrayOf(ContactsContract.RawContacts.CONTACT_ID), null, null, null
            )!!.use { cursor ->
                assertEquals("Owned raw contact must still exist", 1, cursor.count)
                assertTrue(cursor.moveToFirst())
                assertFalse("Owned contact has no aggregate contact id", cursor.isNull(0))
                cursor.getString(0)
            }
            val providerPhone = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID} = ?", arrayOf(rawContactId.toString()), null
            )!!.use { cursor ->
                assertEquals("Owned Amy must have exactly one phone row", 1, cursor.count)
                assertTrue(cursor.moveToFirst())
                JSONObject().put("contactId", cursor.getString(0)).put("name", cursor.getString(1))
                    .put("number", cursor.getString(2))
            }
            assertEquals(contactId, providerPhone.getString("contactId"))
            assertEquals("Amy Demo", providerPhone.getString("name"))
            assertEquals(recipient, providerPhone.getString("number"))
            val draftRecord = JSONObject(File(File(proposal.imagePreviewPath!!).parentFile!!, "record.json").readText())
            assertEquals("Draft recipient did not come from this run's contact", contactId, draftRecord.getString("contactId"))
            assertEquals(providerPhone.getString("name"), draftRecord.getString("name"))
            assertEquals(providerPhone.getString("number"), draftRecord.getString("number"))
            assertEquals("Draft was already submitted", "prepared", draftRecord.getString("state"))
            output.resolve("prepared-record.json").writeText(draftRecord.toString(2) + "\n")
            report.put("recipientResolvedFromContacts", true).put("recipientProviderRow", providerPhone)
                .put("preparedRecord", draftRecord)
            awaitUntil(timeoutMs = 10_000, message = "Send-photo confirmation is not visible") {
                val visible = nodes().filter { it.isVisibleToUser }
                visible.any { it.text?.toString() == "Send photo?" } &&
                    visible.any { it.className?.toString()?.endsWith("ImageView") == true &&
                        it.contentDescription?.toString() == "Photo to send" } &&
                    visible.any { it.text?.toString() == "Send photo" && it.isEnabled && it.isClickable }
            }
            report.put("confirmation", proposal.description).put("imagePreviewPath", proposal.imagePreviewPath)
                .put("confirmationDialogVisible", true).put("proposalCount", proposals.size)
            phase("confirmation-visible")
            screenshot("confirmation")
            hold()
            val trace = captureTrace()
            assertTrue("Current-run trace missing", trace.isNotBlank())
            val toolCalls = Regex("TOOL CALL\\s+#\\d+\\s+(\\w+)").findAll(trace).map { it.groupValues[1] }.toList()
            report.put("toolCalls", JSONArray(toolCalls))
            assertTrue("Live model did not search photos", "search_files" in toolCalls)
            val searchArgs = Regex("TOOL CALL\\s+#\\d+\\s+search_files\\s+(\\{[^\\r\\n]*\\})")
                .findAll(trace).map { JSONObject(it.groupValues[1]) }.toList()
            assertTrue("Live photo search omitted the requested capture date", searchArgs.any {
                it.optString("captured_on") in setOf("yesterday", expectedDay.toString()) ||
                    (it.optString("captured_after") == expectedDay.toString() &&
                        it.optString("captured_before") == expectedDay.plusDays(1).toString())
            })
            report.put("captureDateToolArgumentVerified", true)
            assertTrue("Live model did not prepare MMS", "send_photo" in toolCalls)
            val sendPhotoArgs = Regex("TOOL CALL\\s+#\\d+\\s+send_photo\\s+(\\{[^\\r\\n]*\\})")
                .findAll(trace).map { JSONObject(it.groupValues[1]) }.toList()
            assertEquals("Expected one photo preparation", 1, sendPhotoArgs.size)
            val sendArgs = sendPhotoArgs.single()
            assertEquals("Model must supply only the requested contact name", setOf("contact_name"),
                sendArgs.keys().asSequence().toSet())
            assertTrue("Photo was requested for Amy", sendArgs.getString("contact_name").trim().let {
                it.equals("Amy", ignoreCase = true) || it.equals("Amy Demo", ignoreCase = true)
            })
            report.put("optionalLookupContactCalled", "lookup_contact" in toolCalls).put("sendPhotoArgs", sendArgs)
                .put("modelSuppliedRecipientNumberOrPath", false)
            assertFalse("Text-message route must not be used", "send_sms" in toolCalls)
            assertFalse("Native composer route must not be used", "compose_message" in toolCalls)
            assertEquals("Exactly the two short requests must suffice", listOf(firstRequest, secondRequest),
                ChatStore.messages.value.filter { it.role == ChatMessage.Role.USER }.map { it.content })
            checkOutboundUnchanged()
            report.put("status", "passed")
        } catch (t: Throwable) {
            report.put("status", "failed").put("error", "${t.javaClass.simpleName}: ${t.message}")
            runCatching { captureTrace() }.onFailure {
                report.put("traceCaptureError", "${it.javaClass.simpleName}: ${it.message}")
            }
            runCatching { screenshot("failure") }.onFailure {
                report.put("failureCaptureError", "${it.javaClass.simpleName}: ${it.message}")
            }
            throw t
        } finally {
            PendingAction.beginDemoReset()
            try {
                ui.dropShellPermissionIdentity()
                withContext(Dispatchers.Main) {
                    vm?.cancel()
                    val jobs = vm?.viewModelScope?.coroutineContext?.get(Job)?.children?.toList().orEmpty()
                    jobs.forEach { it.cancel() }
                    jobs.forEach { it.join() }
                }
                // This is the only confirmation action allowed by the runner.
                nodes().firstOrNull { it.isVisibleToUser && it.text?.toString() == "Not now" }
                    ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                sessionId?.let { PhotoSelectionStore.clear(it) }
                scope.cancel()
                scope.coroutineContext[Job]?.join()
                ownedContactId?.let { raw ->
                    ui.adoptShellPermissionIdentity(Manifest.permission.WRITE_CONTACTS)
                    try {
                        context.contentResolver.delete(ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, raw), null, null)
                        report.put("ownedContactRemoved", true)
                    } finally { ui.dropShellPermissionIdentity() }
                }
                checkOutboundUnchanged()
                withContext(Dispatchers.Main) { ChatStore.switchTo(oldSession) }
                scenario?.close()
                if (hadTrace) prefs.edit().putBoolean(AppSettings.KEY_REASONING_TRACE, oldTrace).commit()
                else prefs.edit().remove(AppSettings.KEY_REASONING_TRACE).commit()
                report.put("assistantRemainedPaused", AgentRunState.isPaused)
                    .put("automationRemainedOff", !AppSettings.fullAutomation)
                    .put("traceSettingRestored", prefs.getBoolean(AppSettings.KEY_REASONING_TRACE, false) == oldTrace)
            } catch (cleanup: Throwable) {
                report.put("cleanupError", "${cleanup.javaClass.simpleName}: ${cleanup.message}").put("status", "failed")
            } finally {
                ui.dropShellPermissionIdentity()
                runCatching { verifyDraftCleanup() }.onFailure {
                    report.put("draftCleanupError", "${it.javaClass.simpleName}: ${it.message}")
                        .put("cleanupError", "${it.javaClass.simpleName}: ${it.message}").put("status", "failed")
                }
                runCatching { captureTrace() }.onFailure {
                    report.put("traceCaptureError", "${it.javaClass.simpleName}: ${it.message}")
                }
                if (!report.has("cleanupError")) PendingAction.finishDemoReset()
                report.put("elapsedMs", SystemClock.elapsedRealtime() - started)
                output.resolve("report.json").writeText(report.toString(2) + "\n")
            }
            assertFalse("Cleanup failed; inspect report before another run", report.has("cleanupError"))
        }
    }
}
