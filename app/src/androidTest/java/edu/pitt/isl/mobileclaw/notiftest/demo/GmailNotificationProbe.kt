package edu.pitt.isl.mobileclaw.notiftest.demo

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.pitt.isl.mobileclaw.notiftest.AgentRunState
import edu.pitt.isl.mobileclaw.notiftest.NotifListener
import edu.pitt.isl.mobileclaw.notiftest.awaitUntil
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Read-only receipt probe: only a uniquely marked Gmail test notification is exported. */
@RunWith(AndroidJUnit4::class)
class GmailNotificationProbe {
    @Test fun inspectMarkedIncomingMailWithoutModel() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in to the real Gmail receipt probe", args.getString("gmailReceiptProbe") == "true")
        val marker = requireNotNull(args.getString("gmailMarker"))
        require(marker.matches(Regex("MCMAIL-[A-Z0-9-]{1,40}")))
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue("Keep the assistant paused during real-mail setup", AgentRunState.isPaused)
        NotificationListenerService.requestRebind(ComponentName(context, NotifListener::class.java))
        awaitUntil(timeoutMs = 15_000) { NotifListener.instance != null }
        var matched = emptyList<android.service.notification.StatusBarNotification>()
        awaitUntil(timeoutMs = 90_000, message = "No individual Gmail test notification containing $marker") {
            matched = NotifListener.instance!!.activeNotifications.orEmpty().filter { sbn ->
                if (sbn.packageName != "com.google.android.gm" ||
                    sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return@filter false
                val extras = sbn.notification.extras
                listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT)
                    .any { extras.getCharSequence(it)?.toString()?.contains(marker) == true } ||
                    extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).orEmpty().any { it.contains(marker) }
            }
            matched.isNotEmpty()
        }
        assertEquals("Use one unique test marker, never an aggregate inbox notification", 1, matched.size)
        val sbn = matched.single()
        val notification = sbn.notification
        val extras = notification.extras
        val selected = JSONObject().put("key", sbn.key).put("package", sbn.packageName)
            .put("postTimeMs", sbn.postTime).put("category", notification.category)
            .put("channel", notification.channelId).put("groupSummary", false)
        for (key in listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUB_TEXT, Notification.EXTRA_TEMPLATE)) {
            selected.put(key, extras.getCharSequence(key)?.toString().orEmpty())
        }
        selected.put("textLines", JSONArray(extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).orEmpty().map { it.toString() }))
        val result = JSONObject().put("status", "passed").put("source", "actual Gmail posted notification")
            .put("marker", marker).put("liveLlm", false).put("assistantRemainedPaused", AgentRunState.isPaused)
            .put("notification", selected)
        File(context.filesDir, "demo-mail-board/$marker/gmail-receipt.json").apply {
            parentFile!!.mkdirs(); writeText(result.toString(2) + "\n")
        }
        assertTrue("Probe must not resume notification inference", AgentRunState.isPaused)
    }
}
