package edu.pitt.isl.mobileclaw.notiftest

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.preference.PreferenceManager
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The notification confirmation path is [PendingAction]'s fallback when the app
 * is backgrounded without overlay permission.
 *
 * Instrumented rather than JVM because every interesting part of it is framework: real
 * `NotificationManager` state, real `PendingIntent` delivery to a real
 * `BroadcastReceiver`. The presses here are the actual `PendingIntent`s read back off
 * the posted notification, not calls to [NotifConfirm.resolve] — the wiring between the
 * button and the receiver is exactly the part a unit test would have had to assume.
 *
 * An instrumentation run is never foregrounded, which is what makes the background
 * paths reachable at all.
 */
@RunWith(AndroidJUnit4::class)
class NotifConfirmTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val nm: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun setUp() {
        AppSettings.init(context)
        NotifConfirm.init(context)
        // Full Automation would execute proposals outright and never show anything.
        // Written through the same SharedPreferences the Settings switch uses, since
        // AppSettings reads it live rather than caching.
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putBoolean(AppSettings.KEY_FULL_AUTOMATION, false).commit()
        clearConfirmations()
        // Granted, not assumed. `POST_NOTIFICATIONS` is a runtime permission on API 33+
        // and nothing in the instrumented run grants it, so the `assumeTrue` this replaces
        // skipped **every case in this class** on any modern device — silently, and
        // reported as green. The skip was written for a device where the user had turned
        // notifications off; the common case turned out to be a fresh install.
        if (Build.VERSION.SDK_INT >= 33) {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS")
        }
        assumeTrue(
            "notifications are disabled for the app — nothing to test",
            androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        )
    }

    @After
    fun tearDown() = clearConfirmations()

    @Test
    fun aBackgroundProposalBecomesAnAnswerableNotification() {
        val proposal = proposal("Send SMS to Alex: on my way")

        assertTrue("tryShow should have posted", NotifConfirm.tryShow(proposal))

        val posted = awaitConfirmation()
        assertEquals("Confirm action", posted.notification.extras.getString("android.title"))
        assertTrue(
            "the description is what the user is agreeing to and must be shown",
            posted.notification.extras.getString("android.text")!!.contains("Send SMS to Alex")
        )
        assertEquals("expected Reject and Approve", 2, posted.notification.actions.size)
        assertEquals("Reject", posted.notification.actions[0].title)
        assertEquals("Approve", posted.notification.actions[1].title)
    }

    @Test
    fun approvingRequiresAnUnlockedDevice() {
        // Without this the shade of a locked phone is an approve button for anything the
        // agent proposes — including privileged commands, which are exempt from Full
        // Automation precisely so that a human has to agree to them. Rejecting does not
        // need it: refusing to act is always safe.
        assumeTrue("setAuthenticationRequired is API 31+", Build.VERSION.SDK_INT >= 31)
        NotifConfirm.tryShow(proposal("Delete /sdcard/DCIM (recursive)"))

        val actions = awaitConfirmation().notification.actions

        assertTrue("Approve must require authentication", actions[1].isAuthenticationRequired)
        assertFalse("Reject need not", actions[0].isAuthenticationRequired)
    }

    @Test
    fun approvingRunsTheActionAndClearsTheNotification() {
        val ran = AtomicBoolean(false)
        NotifConfirm.tryShow(proposal("Dismiss 3 notifications", ran))

        press(awaitConfirmation(), APPROVE)

        awaitUntil(message = "the approved action never ran") { ran.get() }
        awaitUntil(message = "the notification should be withdrawn once answered") {
            activeConfirmations().isEmpty()
        }
        assertEquals("nothing should still be awaiting an answer", 0, NotifConfirm.awaitingCount)
    }

    @Test
    fun rejectingRunsNothingAndSaysSoInTheAuditTrail() {
        val ran = AtomicBoolean(false)
        val description = "Reply to Priya: sounds good ${System.currentTimeMillis()}"
        NotifConfirm.tryShow(proposal(description, ran))

        press(awaitConfirmation(), REJECT)

        awaitUntil(message = "the rejection was never recorded") {
            ActionLogStore.entries.value.any {
                it.actionTitle == description && it.result.contains("Rejected by user (notification)")
            }
        }
        assertFalse("a rejected action must not have run", ran.get())
        assertEquals(0, NotifConfirm.awaitingCount)
    }

    @Test
    fun swipingTheConfirmationAwayIsAnAnswer() {
        // Otherwise the proposal outlives the only thing that could resolve it, and sits
        // in memory waiting for an approval that can never arrive.
        val ran = AtomicBoolean(false)
        NotifConfirm.tryShow(proposal("Force-stop com.example.app", ran))
        val posted = awaitConfirmation()

        posted.notification.deleteIntent.send()

        awaitUntil(message = "dismissal should have dropped the proposal") {
            NotifConfirm.awaitingCount == 0
        }
        assertFalse("dismissing must not execute anything", ran.get())
    }

    @Test
    fun anAnswerThisProcessNoLongerRemembersIsRecordedRatherThanSwallowed() {
        // The asymmetry this whole class is built around: the notification outlives the
        // process, the proposal map does not. From the user's side they pressed Approve
        // and nothing happened, so the log is the only place that can explain why.
        val unknownId = 424242

        NotifConfirm.resolve(unknownId, approved = true)

        awaitUntil(message = "an unresolvable confirmation was silently dropped") {
            ActionLogStore.entries.value.any {
                it.actionTitle.contains("#$unknownId") && it.result.contains("restarted or the confirmation expired")
            }
        }
    }

    @Test
    fun demoResetWithdrawsConfirmationsAndInvalidatesAnAlreadySavedApproveIntent() {
        val ran = AtomicBoolean(false)
        NotifConfirm.tryShow(proposal("Old demo action", ran))
        val posted = awaitConfirmation()

        PendingAction.resetForDemo()

        assertEquals(0, NotifConfirm.awaitingCount)
        awaitUntil(message = "reset left an old confirmation visible") {
            activeConfirmations().isEmpty()
        }
        // Retaining the actual intent covers an approval already handed to Android,
        // not just the easy case where reset hid its button before it could be tapped.
        press(posted, APPROVE)
        awaitUntil(message = "saved approval was not handled") {
            ActionLogStore.entries.value.any {
                it.actionTitle.contains("#${posted.id}") && it.result.contains("Not executed")
            }
        }
        assertFalse("an approval from the previous demo must not send anything", ran.get())

        val newRan = AtomicBoolean(false)
        assertTrue(NotifConfirm.tryShow(proposal("New demo action", newRan)))
        press(awaitConfirmation(), APPROVE)
        awaitUntil(message = "reset must leave new confirmations usable") { newRan.get() }
    }

    @Test
    fun demoResetInvalidatesAProposalAlreadyDeliveredToADialog() = runBlocking {
        val ran = AtomicBoolean(false)
        val old = proposal("Old dialog action", ran)

        PendingAction.resetForDemo()

        assertFalse(PendingAction.isCurrent(old))
        assertNull(PendingAction.executeIfCurrent(old))
        assertFalse("a retained dialog callback must not execute its old action", ran.get())
        val fresh = proposal("Fresh dialog action", ran)
        assertTrue(PendingAction.isCurrent(fresh))
        assertEquals("executed", PendingAction.executeIfCurrent(fresh))
        assertTrue(ran.get())
    }

    @Test
    fun anOldQueuedProposalCannotRepostAConfirmationAfterDemoReset() {
        val old = proposal("Queued before reset")

        PendingAction.resetForDemo()
        PendingAction.propose(old)
        NotifConfirm.tryShow(old)

        assertEquals(0, NotifConfirm.awaitingCount)
        assertTrue(activeConfirmations().isEmpty())
    }

    @Test
    fun proposalsCreatedWhileOldTurnsDrainStayInvalidAfterResetFinishes() = runBlocking {
        val ran = AtomicBoolean(false)
        lateinit var duringReset: PendingAction.Proposal
        PendingAction.beginDemoReset()
        try {
            duringReset = proposal("Cancelling turn's late proposal", ran)
            assertFalse(PendingAction.isCurrent(duringReset))
            assertNull(PendingAction.executeIfCurrent(duringReset))
            PendingAction.propose(duringReset)
            assertEquals(0, NotifConfirm.awaitingCount)
        } finally {
            PendingAction.finishDemoReset()
        }
        assertFalse(PendingAction.isCurrent(duringReset))
        assertNull(PendingAction.executeIfCurrent(duringReset))
        assertFalse(ran.get())
        assertEquals("executed", PendingAction.executeIfCurrent(proposal("Next demo", ran)))
        assertTrue(ran.get())
    }

    @Test
    fun theGateFallsThroughToANotificationWhenNoOverlayIsAvailable() {
        // The ordering in PendingAction.propose, end to end. Skipped rather than faked if
        // this device happens to have the overlay permission — the overlay is the better
        // path and is supposed to win when it exists.
        assumeTrue(
            "overlay permission is granted, so the overlay path wins here",
            !Settings.canDrawOverlays(context)
        )
        val description = "Click 'Archive' on the email ${System.currentTimeMillis()}"

        PendingAction.propose(PendingAction.Proposal(description = description) { "done" })

        val posted = awaitConfirmation()
        assertTrue(
            "expected the proposal, got: ${posted.notification.extras.getString("android.text")}",
            posted.notification.extras.getString("android.text")!!.contains(description)
        )
    }

    // ---- helpers ---------------------------------------------------------------

    private fun proposal(description: String, ran: AtomicBoolean = AtomicBoolean(false)) =
        PendingAction.Proposal(description = description) {
            ran.set(true)
            "executed"
        }

    private fun activeConfirmations(): List<StatusBarNotification> =
        nm.activeNotifications.filter { it.notification.channelId == NotifConfirm.CHANNEL_ID }

    private fun awaitConfirmation(): StatusBarNotification {
        awaitUntil(message = "no confirmation notification was posted") {
            activeConfirmations().isNotEmpty()
        }
        return activeConfirmations().first().also { assertNotNull(it.notification.actions) }
    }

    /** Presses a button the way the shade would: by sending the action's own intent. */
    private fun press(posted: StatusBarNotification, index: Int) {
        posted.notification.actions[index].actionIntent.send()
    }

    /**
     * Leftovers are *answered*, not merely cancelled. [NotifConfirm.awaitingCount] is
     * process-wide, so a test that hid a previous test's notification without resolving
     * it would leave the proposal in the map and make every later count assertion read
     * one too high — which is exactly what a stray `nm.cancel` does in production too.
     */
    private fun clearConfirmations() {
        activeConfirmations().forEach { NotifConfirm.resolve(it.id, approved = false) }
    }

    private companion object {
        const val REJECT = 0
        const val APPROVE = 1
    }
}
