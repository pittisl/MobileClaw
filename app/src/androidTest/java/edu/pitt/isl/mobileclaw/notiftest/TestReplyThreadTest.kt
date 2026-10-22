package edu.pitt.isl.mobileclaw.notiftest

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Notification 13's inline reply, end to end.
 *
 * The press is the real `PendingIntent` read back off the posted notification and filled
 * with a real `RemoteInput` result, the same technique [NotifConfirmTest] uses — not a
 * direct call to the receiver, which would prove only that a function runs.
 *
 * What makes this worth a test rather than a manual check: #13 exists so the click path
 * can be verified on a device, and a reply that changed nothing in the shade made *that*
 * unverifiable in exactly the way the placeholder buttons on 1–12 are. The second
 * assertion is the safety one — the thread that comes back carries no reply action, which
 * is what stops the agent from being able to answer its own answer when triage sees the
 * update.
 */
@RunWith(AndroidJUnit4::class)
class TestReplyThreadTest {

    private lateinit var context: Context
    private lateinit var nm: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Granted rather than assumed. `NotifConfirmTest` skips itself when notifications
        // are off, which on a fresh install under API 33+ is *always* — the permission is
        // a runtime one and nothing grants it. A test that silently skips proves nothing
        // and reads as green, so this one takes the grant it needs.
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName, "android.permission.POST_NOTIFICATIONS"
            )
        }
        nm.cancel(EMAIL_ID)
    }

    @After
    fun tearDown() {
        nm.cancel(EMAIL_ID)
    }

    private fun posted(): Notification? =
        nm.activeNotifications.firstOrNull { it.id == EMAIL_ID }?.notification

    private fun replyAction(n: Notification): Notification.Action? =
        n.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }

    @Test
    fun replyingToTheTestEmailTurnsItIntoAThreadThatCannotAnswerItself() {
        NotificationTester(context).fireActionableEmail()
        awaitUntil(message = "the test email never posted") { posted() != null }

        val action = replyAction(posted()!!)
        assertNotNull("#13 must carry an inline reply action", action)

        // Fill the RemoteInput exactly as the system does when a user types into the
        // shade, then fire the action's own PendingIntent.
        val filled = Intent()
        android.app.RemoteInput.addResultsToIntent(
            action!!.remoteInputs, filled,
            Bundle().apply { putCharSequence(TestActionReceiver.REMOTE_INPUT_KEY, REPLY) }
        )
        action.actionIntent.send(context, 0, filled)

        awaitUntil(message = "the thread never appeared") {
            posted()?.let { messagesOf(it).any { m -> m.text == REPLY } } == true
        }

        val thread = posted()!!
        val messages = messagesOf(thread)
        // A null person is the local user: the reply has to read as sent, not received,
        // or the update says nothing about whether it was delivered.
        assertTrue(
            "the reply must render as sent by the user",
            messages.any { it.text == REPLY && it.person == null }
        )
        assertTrue(
            "the original message must survive, or the update is not a thread",
            messages.any { it.text?.contains("confirm the new deadline") == true }
        )
        assertFalse(
            "an answered thread must not offer another reply",
            replyAction(thread) != null
        )
    }

    private fun messagesOf(n: Notification): List<NotificationCompat.MessagingStyle.Message> =
        NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(n)?.messages.orEmpty()

    companion object {
        private const val EMAIL_ID = 13
        private const val REPLY = "Next Friday works — thanks."
    }
}
