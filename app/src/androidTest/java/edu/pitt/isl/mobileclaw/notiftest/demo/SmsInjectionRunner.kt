package edu.pitt.isl.mobileclaw.notiftest.demo

import android.Manifest
import android.app.PendingIntent
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.pitt.isl.mobileclaw.notiftest.AgentRunState
import edu.pitt.isl.mobileclaw.notiftest.AppSettings
import edu.pitt.isl.mobileclaw.notiftest.NotifListener
import edu.pitt.isl.mobileclaw.notiftest.NotifStore
import edu.pitt.isl.mobileclaw.notiftest.awaitUntil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit phone probe: injects one inbound fixture; never sends a reply or invokes a model. */
@RunWith(AndroidJUnit4::class)
class SmsInjectionRunner {
    @Test fun fixtureReachesSystemInboxNotificationAndCapture() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with -e injectSms true", args.getString("injectSms") == "true")
        val nonce = args.getString("smsNonce") ?: "MCINJ-${System.currentTimeMillis()}"
        require(nonce.matches(Regex("MCINJ-[A-Z0-9-]{1,35}")))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ui = instrumentation.uiAutomation
        val sender = "+12025550143" // NANP reserved fictional 555-01xx block.
        val body = "MobileClaw test $nonce. No reply needed."
        // The production app does not declare visibility for SMS packages. Read the
        // role through shell instead of treating a filtered package lookup as no SMS app.
        val smsPackage = ui.executeShellCommand("cmd role get-role-holders --user 0 android.app.role.SMS").use { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().readText().trim()
        }
        require(smsPackage.matches(Regex("[A-Za-z0-9_.]+"))) { "No unambiguous default SMS role: $smsPackage" }
        val report = JSONObject().put("nonce", nonce).put("sender", sender).put("body", body)
            .put("defaultSmsApp", smsPackage)
            .put("outboundSms", false).put("liveLlm", false)
        val output = File(context.filesDir, "demo-sms-injection/$nonce.json")
        // UiAutomation executes an argv command, not a shell interpreting quotes.
        // Keep the SQL predicate one argument; the nonce allowlist also bounds SQL input.
        val query = "content query --uri content://sms/inbox --projection _id:address:body:sub_id:thread_id " +
            "--where instr(body,'$nonce')>0"
        fun fixtureInbox(): String = ui.executeShellCommand(query).use { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().readText()
        }
        val oldPaused = AgentRunState.isPaused
        val result = CompletableDeferred<Int>()
        val action = "${context.packageName}.SMS_INJECTION.$nonce"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) { result.complete(resultCode) }
        }
        var registered = false
        val started = SystemClock.elapsedRealtime()
        try {
            AppSettings.init(context)
            assertFalse("Turn off Full Automation before probing", AppSettings.fullAutomation)
            instrumentation.runOnMainSync { AgentRunState.setPaused(true) }
            // Instrumentation restarts the app process; rebind the already-authorized
            // listener before probing, as in the phone reset acceptance.
            ui.executeShellCommand("cmd notification allow_listener ${context.packageName}/${NotifListener::class.java.name}").close()
            instrumentation.runOnMainSync {
                NotificationListenerService.requestRebind(ComponentName(context, NotifListener::class.java))
            }
            awaitUntil(timeoutMs = 15_000, message = "notification listener is not connected") { NotifListener.instance != null }
            context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_EXPORTED)
            registered = true
            val callback = PendingIntent.getBroadcast(context, nonce.hashCode(),
                Intent(action).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val pdu = DemoSmsPdu.deliverPdu(sender, body)
            val parsed = SmsMessage.createFromPdu(pdu, "3gpp")
            assertEquals(sender, parsed.originatingAddress)
            assertEquals(body, parsed.messageBody)
            assertEquals(SmsMessage.MessageClass.CLASS_1, parsed.messageClass)
            report.put("pduBytes", pdu.size).put("messageClass", parsed.messageClass.name)
            ui.adoptShellPermissionIdentity(Manifest.permission.MODIFY_PHONE_STATE,
                Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_SMS)
            val subscriptions = context.getSystemService(SubscriptionManager::class.java)
                .activeSubscriptionInfoList.orEmpty()
            val preferred = SubscriptionManager.getDefaultSmsSubscriptionId()
            val subId = subscriptions.firstOrNull { it.subscriptionId == preferred }?.subscriptionId
                ?: subscriptions.firstOrNull()?.subscriptionId
                ?: error("No active subscription")
            report.put("subscriptionId", subId).put("permissionPath", "adoptShellPermissionIdentity")
            assertFalse("Use a fresh nonce; this fixture is already in the inbox", fixtureInbox().contains(nonce))
            context.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
                .injectSmsPdu(pdu, "3gpp", callback)
            val code = withTimeout(20_000) { result.await() }
            report.put("injectionResultCode", code)
            assertEquals("Telephony rejected the injected PDU", Telephony.Sms.Intents.RESULT_SMS_HANDLED, code)
            // Check only this nonce, never dump the user's inbox. Shell READ_SMS avoids
            // the normal app's SMS-provider restrictions without changing its permissions.
            var inbox = ""
            awaitUntil(timeoutMs = 20_000, message = "fixture did not reach the system inbox") {
                inbox = fixtureInbox()
                report.put("inboxQueryLastResult", inbox.trim())
                inbox.contains(nonce) && inbox.contains(sender)
            }
            report.put("inboxFixture", inbox.trim())
            var notificationKey = ""
            awaitUntil(timeoutMs = 20_000, message = "default SMS app did not post the fixture notification") {
                val sbn = NotifListener.instance?.activeNotifications.orEmpty().firstOrNull {
                    it.packageName == smsPackage &&
                        it.notification.extras.getCharSequence("android.text").toString().contains(nonce)
                }
                notificationKey = sbn?.key.orEmpty()
                sbn != null
            }
            report.put("notificationKey", notificationKey).put("captureMode", "resume-reseed; triage suppressed")
            // Pause blocks capture too. Resume/reseed treats the existing shade as stale,
            // allowing capture to be checked without spending a triage/follow-up call.
            instrumentation.runOnMainSync {
                AgentRunState.setPaused(false)
                NotifListener.instance!!.onRunStateResumed()
            }
            val captured = NotifStore.events.value.firstOrNull {
                it.key == notificationKey && (it.text.contains(nonce) || it.title.contains(nonce))
            }
            assertNotNull("MobileClaw did not capture the Messaging notification", captured)
            report.put("capturedPackage", captured!!.packageName)
                .put("capturedText", captured.text).put("messagingStyle", captured.isMessaging)
                .put("notificationActions", captured.actions.map { it.title }.joinToString(", "))
                .put("status", "passed")
        } catch (t: Throwable) {
            report.put("status", "failed").put("error", "${t.javaClass.simpleName}: ${t.message}")
            throw t
        } finally {
            ui.dropShellPermissionIdentity()
            if (registered) context.unregisterReceiver(receiver)
            instrumentation.runOnMainSync { AgentRunState.setPaused(oldPaused) }
            report.put("pausedStateRestored", AgentRunState.isPaused == oldPaused)
                .put("elapsedMs", SystemClock.elapsedRealtime() - started)
            output.parentFile!!.mkdirs()
            output.writeText(report.toString(2) + "\n")
            android.util.Log.i("MobileClaw-SMS-PROBE", "${report.optString("status")}: ${output.absolutePath}")
        }
    }

    /** Read back an existing fixture's action metadata without injecting another message. */
    @Test fun inspectExistingFixtureNotification() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val nonce = InstrumentationRegistry.getArguments().getString("inspectSmsNonce")
        assumeTrue("Opt in with -e inspectSmsNonce MCINJ-...", nonce != null)
        require(nonce!!.matches(Regex("MCINJ-[A-Z0-9-]{1,35}")))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val oldPaused = AgentRunState.isPaused
        try {
            instrumentation.runOnMainSync { AgentRunState.setPaused(true) }
            instrumentation.uiAutomation.executeShellCommand(
                "cmd notification allow_listener ${context.packageName}/${NotifListener::class.java.name}").close()
            instrumentation.runOnMainSync {
                NotificationListenerService.requestRebind(ComponentName(context, NotifListener::class.java))
            }
            awaitUntil(timeoutMs = 15_000) { NotifListener.instance != null }
            val fixture = NotifListener.instance!!.activeNotifications.orEmpty().first {
                it.notification.extras.getCharSequence("android.text").toString().contains(nonce)
            }
            val n = fixture.notification
            val report = JSONObject().put("nonce", nonce).put("package", fixture.packageName)
                .put("category", n.category).put("template", n.extras.getString("android.template"))
                .put("replyAcceptsText", n.actions.orEmpty().any {
                    it.remoteInputs.orEmpty().any { input -> input.allowFreeFormInput }
                }).put("injectedAnotherSms", false).put("liveLlm", false).put("outboundSms", false)
            val output = File(context.filesDir, "demo-sms-injection/$nonce-notification.json")
            output.parentFile!!.mkdirs()
            output.writeText(report.toString(2) + "\n")
            assertEquals("com.android.messaging", fixture.packageName)
            assertTrue("No native free-form reply action", report.getBoolean("replyAcceptsText"))
        } finally {
            instrumentation.runOnMainSync { AgentRunState.setPaused(oldPaused) }
        }
    }

}
