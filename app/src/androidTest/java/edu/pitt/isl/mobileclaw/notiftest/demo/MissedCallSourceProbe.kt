package edu.pitt.isl.mobileclaw.notiftest.demo

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.ParcelFileDescriptor
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
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

/** Explicit offline source probe. Telecom, never this fixture, posts the missed-call notification. */
@RunWith(AndroidJUnit4::class)
class MissedCallSourceProbe {
    @Test fun managedIncomingCallCreatesNativeMissedNotification() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with -e missedCallSource true", args.getString("missedCallSource") == "true")
        val runId = args.getString("missedCallRunId") ?: error("Explicit unique run ID required")
        ManagedIncomingCallFixture.injectAndObserve(runId)
    }
}

object ManagedIncomingCallFixture {
    const val TEST_PACKAGE = "edu.pitt.isl.mobileclaw.notiftest.test"
    private const val RECEIVER = "edu.pitt.isl.mobileclaw.notiftest.demo.DemoIncomingCallReceiver"
    private const val SERVICE = "edu.pitt.isl.mobileclaw.notiftest.demo.DemoIncomingConnectionService"

    fun shell(command: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommand(command).use { fd ->
            ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().readText().trim()
        }

    /** Leaves the native notification and only the fictional call log row; unregisters its account. */
    suspend fun injectAndObserve(runId: String, dismissPreviousFixture: Boolean = false): StatusBarNotification {
        require(runId.matches(Regex(DemoIncomingConnectionService.RUN_ID)))
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue("Source fixture must never enable background inference", AgentRunState.isPaused)
        val report = JSONObject().put("runId", runId).put("liveLlm", false)
            .put("outboundCalls", 0).put("outboundSms", 0)
        val output = File(context.filesDir, "demo-missed-call/$runId/source.json")
        val started = System.currentTimeMillis()
        fun stage(stage: String): String = shell("am broadcast --include-stopped-packages --receiver-foreground -a ${DemoIncomingCallReceiver.ACTION} " +
            "-n $TEST_PACKAGE/$RECEIVER --es runId $runId --es stage $stage")
        fun serviceReport(): JSONObject {
            val text = shell("run-as $TEST_PACKAGE cat files/demo-incoming-call/$runId.json")
            return if (text.startsWith("{")) JSONObject(text) else JSONObject().put("readError", text)
        }
        try {
            val dialer = shell("cmd telecom get-default-dialer").trim()
            require(dialer.matches(Regex("[A-Za-z0-9_.]+"))) { "Ambiguous default dialer: $dialer" }
            report.put("defaultDialer", dialer)
            NotificationListenerService.requestRebind(ComponentName(context, NotifListener::class.java))
            awaitUntil(timeoutMs = 15_000) { NotifListener.instance != null }
            if (dismissPreviousFixture) {
                // Dismiss only old cards with the exact fictional caller, never unrelated calls.
                val oldFixtureCards = NotifListener.instance!!.activeNotifications.orEmpty().filter {
                    it.packageName == dialer &&
                        it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 &&
                        it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "Missed call" &&
                        it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == "Morgan Demo"
                }
                oldFixtureCards.forEach { NotifListener.instance!!.cancelNotification(it.key) }
                report.put("dismissedPreviousFixtureCards", JSONArray(oldFixtureCards.map { it.key }))
                kotlinx.coroutines.delay(800)
            }
            val oldKeys = NotifListener.instance!!.activeNotifications.orEmpty().map { it.key to it.postTime }.toSet()
            report.put("registerResult", stage("register"))
            val registered = serviceReport()
            report.put("registered", registered)
            require(registered.optString("stage") == "register") { "Fixture registration failed: $registered" }
            report.put("enableResult", shell("cmd telecom set-phone-account-enabled $TEST_PACKAGE/$SERVICE " +
                "${DemoIncomingConnectionService.ACCOUNT_ID} 0"))
            report.put("injectResult", stage("inject"))
            var service = serviceReport()
            awaitUntil(timeoutMs = 20_000, message = "Telecom never reached the managed fixture's missed stage") {
                service = serviceReport()
                report.put("connection", service)
                if (service.optString("stage").startsWith("failed") ||
                    service.optString("stage").contains("connection-failed")) error("Managed call refused: $service")
                service.optString("stage") == "missed"
            }
            var selected: StatusBarNotification? = null
            awaitUntil(timeoutMs = 20_000, message = "No native missed-call notification for fixture") {
                selected = NotifListener.instance!!.activeNotifications.orEmpty().firstOrNull { sbn ->
                    if (sbn.packageName !in setOf(dialer, "com.android.server.telecom")) return@firstOrNull false
                    if ((sbn.key to sbn.postTime) in oldKeys) return@firstOrNull false
                    if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return@firstOrNull false
                    val extras = sbn.notification.extras
                    val text = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT)
                        .joinToString(" ") { extras.getCharSequence(it)?.toString().orEmpty() }
                    (text.contains("Morgan Demo") || text.filter(Char::isDigit).contains("2025550145")) &&
                        (sbn.notification.category == Notification.CATEGORY_MISSED_CALL || text.contains("Missed", true))
                }
                selected != null
            }
            val sbn = selected!!
            val n = sbn.notification
            val native = JSONObject().put("package", sbn.packageName).put("key", sbn.key).put("postTimeMs", sbn.postTime)
                .put("category", n.category).put("channel", n.channelId)
                .put("actions", JSONArray(n.actions.orEmpty().map { it.title.toString() }))
            for (key in listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT,
                Notification.EXTRA_SUB_TEXT, Notification.EXTRA_TEMPLATE)) {
                native.put(key, n.extras.getCharSequence(key)?.toString().orEmpty())
            }
            report.put("notification", native).put("status", "passed")
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            if (bitmap != null) {
                output.parentFile!!.mkdirs()
                File(output.parentFile, "after-missed.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            return sbn
        } catch (t: Throwable) {
            report.put("status", "failed").put("error", "${t.javaClass.simpleName}: ${t.message}")
            throw t
        } finally {
            report.put("serviceBeforeCleanup", serviceReport())
            report.put("unregisterResult", stage("unregister")).put("serviceAfterCleanup", serviceReport())
            report.put("assistantRemainedPaused", AgentRunState.isPaused)
                .put("elapsedMs", System.currentTimeMillis() - started)
            output.parentFile!!.mkdirs(); output.writeText(report.toString(2) + "\n")
        }
    }
}
