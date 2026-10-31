package edu.pitt.isl.mobileclaw.notiftest.demo

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.preference.PreferenceManager
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
import java.util.concurrent.atomic.AtomicInteger

/** Poster input illustrations only. No client, reader, action executor or foreground chat turn. */
@RunWith(AndroidJUnit4::class)
class NotificationInputScreenshotRunner {
    @Test fun captureTwoLocalNotificationInputs() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with -e notificationInputScreenshots true", args.getString("notificationInputScreenshots") == "true")
        val runId = requireNotNull(args.getString("captureRunId"))
        require(runId.matches(Regex("CLEANINPUT-[A-Z0-9-]{1,40}")))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ui = instrumentation.uiAutomation
        val nm = context.getSystemService(NotificationManager::class.java)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val output = File(context.filesDir, "notification-input-screenshots/$runId").apply { mkdirs() }
        val started = System.currentTimeMillis()
        val report = JSONObject().put("runId", runId).put("startedWallMs", started)
            .put("source", "simulated locally posted notifications from MobileClaw; screenshot-only re-display")
            .put("purpose", "Poster notification inputs only; prior model outputs remain from email C and photo D")
            .put("deliveryVerified", false).put("liveModel", false).put("modelCalls", 0)
            .put("readerCalls", 0).put("outboundActions", 0).put("appPackage", context.packageName)
            .put("device", JSONObject().put("model", Build.MODEL).put("manufacturer", Build.MANUFACTURER)
                .put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT))
        val replyCount = AtomicInteger()
        val proposalCount = AtomicInteger()
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        val action = "${context.packageName}.INPUTSCREENSHOT.$runId"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) { replyCount.incrementAndGet() }
        }
        val emailId = 62931
        val photoId = 62932
        val emailChannel = "poster_input_email_$runId"
        val photoChannel = "poster_input_message_$runId"
        var registered = false
        val oldPaused = AgentRunState.isPaused
        val oldFullAutomation = AppSettings.fullAutomation
        val oldSession = ChatStore.activeSessionId.value
        val hadPaused = prefs.contains(AgentRunState.KEY_PAUSED)
        val oldPausedPref = prefs.getBoolean(AgentRunState.KEY_PAUSED, false)
        var beforeChat: List<ChatMessage>? = null
        var beforeSessions: List<ChatStore.Session>? = null
        var beforeOutgoing: JSONObject? = null
        var beforeNotifications: Set<String>? = null
        fun shell(command: String): String = ui.executeShellCommand(command).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
        }
        fun nodes(node: AccessibilityNodeInfo? = ui.rootInActiveWindow): List<AccessibilityNodeInfo> =
            if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
        fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also { node.getBoundsInScreen(it) }
        fun rect(rect: Rect) = JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))
        fun outgoing(): JSONObject {
            ui.adoptShellPermissionIdentity(Manifest.permission.READ_SMS)
            return try {
                val result = JSONObject()
                for ((name, uri, selection) in listOf(Triple("sms", "content://sms", "type != 1"), Triple("mms", "content://mms", "msg_box != 1"))) {
                    val ids = context.contentResolver.query(Uri.parse(uri), arrayOf("_id"), selection, null, "_id ASC")!!.use { c ->
                        buildList { while (c.moveToNext()) add(c.getLong(0)) }
                    }
                    result.put(name, JSONArray(ids))
                }
                result
            } finally { ui.dropShellPermissionIdentity() }
        }
        fun inputCard(title: String): AccessibilityNodeInfo? {
            val titleNode = nodes().firstOrNull { it.isVisibleToUser && it.text?.toString() == title } ?: return null
            var candidate: AccessibilityNodeInfo? = titleNode
            var fallback: AccessibilityNodeInfo? = null
            while (candidate != null) {
                val box = bounds(candidate)
                val id = candidate.viewIdResourceName.orEmpty()
                if (box.width() > 800 && box.height() in 180..900 &&
                    nodes(candidate).any { it.text?.toString() == "Reply" }) {
                    fallback = candidate
                    if (candidate.className?.toString()?.contains("ExpandableNotificationRow") == true ||
                        id.contains("expandableNotificationRow")) return candidate
                }
                candidate = candidate.parent
            }
            return fallback
        }
        fun capture(name: String, title: String, expanded: Boolean, body: String) {
            awaitUntil(timeoutMs = 10_000, message = "Owned notification not visible: $title") { inputCard(title) != null }
            var card = requireNotNull(inputCard(title))
            val desiredToggle = if (expanded) "Expand" else "Collapse"
            nodes(card).firstOrNull { it.contentDescription?.toString() == desiredToggle && it.isClickable }
                ?.let { assertTrue(it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
            ui.waitForIdle(500, 5_000)
            card = requireNotNull(inputCard(title))
            val cardNodes = nodes(card)
            assertTrue("Reply label missing", cardNodes.any { it.text?.toString() == "Reply" })
            assertFalse("An old demonstration label remains", cardNodes.any { n ->
                val text = n.text?.toString().orEmpty()
                text.contains("SIMULAT", ignoreCase = true) || text.contains("(demo only)", ignoreCase = true)
            })
            if (expanded) assertTrue("Full request is not displayed", cardNodes.any { it.text?.toString() == body })
            val image = requireNotNull(ui.takeScreenshot())
            val box = bounds(card)
            require(box.left >= 0 && box.top >= 0 && box.right <= image.width && box.bottom <= image.height)
            output.resolve("$name-full.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val crop = Bitmap.createBitmap(image, box.left, box.top, box.width(), box.height())
            output.resolve("$name-notification.png").outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
            crop.recycle()
            val tree = JSONArray(nodes().map { n -> JSONObject().put("id", n.viewIdResourceName)
                .put("class", n.className?.toString()).put("text", n.text?.toString())
                .put("description", n.contentDescription?.toString()).put("bounds", rect(bounds(n)))
                .put("visible", n.isVisibleToUser).put("clickable", n.isClickable) })
            output.resolve("$name-ui.json").writeText(tree.toString(2) + "\n")
            report.put(name, JSONObject().put("title", title).put("body", body).put("expanded", expanded)
                .put("actionLabel", "Reply").put("subText", JSONObject.NULL).put("cropBoundsXYXY", rect(box))
                .put("screenWidth", image.width).put("screenHeight", image.height)
                .put("cropNodeId", card.viewIdResourceName).put("cropMethod", "Native screenshot pixels, cropped to visible notification node bounds"))
            image.recycle()
        }
        fun post(id: Int, channel: String, title: String, body: String, category: String) {
            val callback = PendingIntent.getBroadcast(context, id, Intent(action).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            val reply = Notification.Action.Builder(android.R.drawable.ic_menu_send, "Reply", callback)
                .addRemoteInput(RemoteInput.Builder("input_reply").setLabel("Reply").build()).build()
            nm.notify(id, Notification.Builder(context, channel).setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle(title).setContentText(body).setStyle(Notification.BigTextStyle().bigText(body))
                .setCategory(category).setVisibility(Notification.VISIBILITY_PUBLIC).addAction(reply).build())
        }
        try {
            assertTrue("Start from an idle paused phone to exclude pre-existing live work", oldPaused)
            assertFalse("Full Automation must be off", oldFullAutomation)
            assertTrue("Notification permission must already be granted", nm.areNotificationsEnabled())
            withContext(Dispatchers.Main) { AgentRunState.setPaused(true) }
            awaitUntil { ChatStore.sessions.value.isNotEmpty() }
            beforeChat = ChatStore.messages.value.toList()
            beforeSessions = ChatStore.sessions.value.toList()
            beforeNotifications = NotifStore.events.value.map { "${it.key}:${it.postTimeMs}" }.toSet()
            beforeOutgoing = outgoing()
            report.put("before", JSONObject().put("paused", oldPaused).put("fullAutomation", oldFullAutomation)
                .put("activeSession", oldSession).put("chatMessageCount", beforeChat!!.size)
                .put("sessionCount", beforeSessions!!.size).put("outgoing", beforeOutgoing))
            context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
            registered = true
            scope.launch(start = CoroutineStart.UNDISPATCHED) { PendingAction.proposals.collect { proposalCount.incrementAndGet() } }
            nm.createNotificationChannel(NotificationChannel(emailChannel, "Email", NotificationManager.IMPORTANCE_HIGH))
            nm.createNotificationChannel(NotificationChannel(photoChannel, "Message", NotificationManager.IMPORTANCE_HIGH))
            shell("input keyevent KEYCODE_HOME")
            post(emailId, emailChannel, "Morgan Demo — Austin travel details",
                "What is the booking code in our Cedar House PDF? Does our calendar show arrival before check-in?", Notification.CATEGORY_EMAIL)
            shell("cmd statusbar expand-notifications")
            capture("email", "Morgan Demo — Austin travel details", false,
                "What is the booking code in our Cedar House PDF? Does our calendar show arrival before check-in?")
            nm.cancel(emailId)
            shell("cmd statusbar collapse")
            post(photoId, photoChannel, "Amy Demo", "Could you send me the dog photo you took yesterday?", Notification.CATEGORY_MESSAGE)
            shell("cmd statusbar expand-notifications")
            capture("photo", "Amy Demo", true, "Could you send me the dog photo you took yesterday?")
            assertTrue(AgentRunState.isPaused)
            assertEquals(beforeChat, ChatStore.messages.value)
            assertEquals(beforeSessions, ChatStore.sessions.value)
            assertEquals(oldSession, ChatStore.activeSessionId.value)
            assertEquals(beforeNotifications, NotifStore.events.value.map { "${it.key}:${it.postTimeMs}" }.toSet())
            assertEquals(0, proposalCount.get())
            assertEquals(0, replyCount.get())
            assertEquals(beforeOutgoing.toString(), outgoing().toString())
            report.put("status", "passed").put("chatUnchanged", true).put("capturedNotificationStoreUnchanged", true)
                .put("outboundProviderRowsUnchanged", true)
        } catch (t: Throwable) {
            report.put("status", "failed").put("error", "${t.javaClass.simpleName}: ${t.message}")
            runCatching {
                ui.takeScreenshot()?.let { image ->
                    output.resolve("failure-full.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    image.recycle()
                }
                output.resolve("failure-ui.json").writeText(JSONArray(nodes().map { n -> JSONObject()
                    .put("id", n.viewIdResourceName).put("text", n.text?.toString())
                    .put("description", n.contentDescription?.toString()).put("bounds", rect(bounds(n))) }).toString(2))
            }
            throw t
        } finally {
            nm.cancel(emailId)
            nm.cancel(photoId)
            nm.deleteNotificationChannel(emailChannel)
            nm.deleteNotificationChannel(photoChannel)
            if (registered) context.unregisterReceiver(receiver)
            scope.cancel()
            scope.coroutineContext[Job]?.join()
            shell("cmd statusbar collapse")
            withContext(Dispatchers.Main) { AgentRunState.setPaused(oldPaused) }
            if (!hadPaused) prefs.edit().remove(AgentRunState.KEY_PAUSED).commit()
            else prefs.edit().putBoolean(AgentRunState.KEY_PAUSED, oldPausedPref).commit()
            report.put("replyCallbackInvocations", replyCount.get()).put("actionProposals", proposalCount.get())
                .put("pausedRestored", AgentRunState.isPaused == oldPaused)
                .put("fullAutomationUnchanged", AppSettings.fullAutomation == oldFullAutomation)
                .put("activeSessionUnchanged", ChatStore.activeSessionId.value == oldSession)
                .put("ownedNotificationsRemoved", nm.activeNotifications.none { it.id == emailId || it.id == photoId })
                .put("elapsedMs", System.currentTimeMillis() - started)
            output.resolve("report.json").writeText(report.toString(2) + "\n")
            ui.dropShellPermissionIdentity()
        }
    }
}
