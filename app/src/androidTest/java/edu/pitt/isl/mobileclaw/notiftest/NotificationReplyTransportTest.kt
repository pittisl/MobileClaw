package edu.pitt.isl.mobileclaw.notiftest

import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Test APK activity: only echoes text to the test receiver. No SMS API or recipient. */
class ReplyTransportEchoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence("reply")?.toString()
        sendBroadcast(Intent(intent.getStringExtra("callback"))
            .setPackage("edu.pitt.isl.mobileclaw.notiftest").putExtra("echo", reply))
        finish()
    }
}

@RunWith(AndroidJUnit4::class)
class NotificationReplyTransportTest {
    @Test fun senderOptInIsScopedToActivityReplies() {
        assertNull(NotificationReplyTransport.options(false))
        val options = NotificationReplyTransport.options(true)
        if (Build.VERSION.SDK_INT >= 34) {
            assertNotNull(options)
            assertEquals(
                if (Build.VERSION.SDK_INT >= 36) ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                else ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                options!!.pendingIntentBackgroundActivityStartMode
            )
        } else assertNull(options)
        assertFalse(NotificationReplyTransport.SUBMITTED.contains("sent", ignoreCase = true))
    }

    @Test fun approvedReplyReachesTestActivityWithoutSendingSms() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val oldPaused = AgentRunState.isPaused
        val action = "edu.pitt.isl.mobileclaw.test.ECHO." + UUID.randomUUID()
        val received = CountDownLatch(1)
        val text = AtomicReference<String?>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                text.set(intent.getStringExtra("echo")); received.countDown()
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_EXPORTED)
        var pending: PendingIntent? = null
        try {
            instrumentation.runOnMainSync { AgentRunState.setPaused(true) }
            ActivityScenario.launch(MainActivity::class.java).use {
                val intent = Intent().setClassName(instrumentation.context.packageName, ReplyTransportEchoActivity::class.java.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("callback", action)
                pending = PendingIntent.getActivity(context, 77129, intent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE)
                val input = RemoteInput.Builder("reply").setLabel("Reply").build()
                val fill = Intent()
                RemoteInput.addResultsToIntent(arrayOf(input), fill, Bundle().apply { putCharSequence("reply", "OFFLINE ECHO ONLY") })
                NotificationReplyTransport.send(context, pending!!, fill)
                assertTrue("The test activity did not receive the reply", received.await(10, TimeUnit.SECONDS))
                assertEquals("OFFLINE ECHO ONLY", text.get())
            }
        } finally {
            pending?.cancel()
            context.unregisterReceiver(receiver)
            instrumentation.runOnMainSync {
                AgentRunState.setPaused(oldPaused)
                if (!oldPaused) NotifListener.instance?.onRunStateResumed()
            }
        }
    }
}
