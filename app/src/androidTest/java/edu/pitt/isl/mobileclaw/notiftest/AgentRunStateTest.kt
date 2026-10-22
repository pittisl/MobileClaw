package edu.pitt.isl.mobileclaw.notiftest

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The run-state control: the persisted pause flag and the sticky notification
 * that carries it.
 *
 * Instrumented rather than JVM for both halves. [AgentRunState] is one boolean and its
 * only interesting property is that it reaches disk, which needs real
 * `SharedPreferences` — the same reasoning as `ShellGateInstrumentedTest`. The
 * notification half needs real `NotificationManager` state and real `PendingIntent`
 * delivery, and presses the buttons by sending the intents read back off the posted
 * notification rather than calling the receiver, following [NotifConfirmTest]: the
 * wiring between the button and the flag is exactly what a unit test would assume.
 */
@RunWith(AndroidJUnit4::class)
class AgentRunStateTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val nm: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun setUp() {
        AgentRunState.init(context)
        RunStateNotification.init(context)
        AgentRunState.setPaused(false)
    }

    @After
    fun tearDown() {
        AgentRunState.setPaused(false)
        RunStateNotification.cancel()
    }

    // ---- the flag ---------------------------------------------------------------

    @Test
    fun aPauseIsWrittenThroughToStorage() {
        // The load-bearing property of the whole feature is that a pause survives process
        // death — otherwise the user believes they turned the agent off, stops watching,
        // and it quietly starts calling the cloud again on the next restart.
        //
        // What this asserts is the half that is reachable from a test running *in* the
        // process: the flag reaches SharedPreferences rather than living only in the
        // singleton's field. It cannot distinguish commit() from apply(), since both
        // update the in-memory map straight away and only the disk flush differs — that
        // choice is argued at the call site instead.
        AgentRunState.setPaused(true)

        val stored = PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(AgentRunState.KEY_PAUSED, false)

        assertTrue("the pause never left the singleton", stored)
        AgentRunState.init(context)
        assertTrue("re-reading storage lost the pause", AgentRunState.isPaused)
    }

    @Test
    fun theDefaultIsRunning() {
        // A fresh installation starts with background capture enabled. An explicit pause
        // is persisted and restored separately.
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().remove(AgentRunState.KEY_PAUSED).commit()

        AgentRunState.init(context)

        assertFalse(AgentRunState.isPaused)
    }

    @Test
    fun aNoOpChangeIsReportedAsSuch() {
        // Callers re-post the notification and reseed the store on a change; doing that
        // for a second press of Pause is wasted work at best.
        assertTrue("running → paused is a change", AgentRunState.setPaused(true))
        assertFalse("paused → paused is not", AgentRunState.setPaused(true))
    }

    @Test
    fun theFlowTracksTheFlag() {
        // What the status strip binds to, so that pressing Pause in the shade updates the
        // open Activity rather than leaving the two disagreeing.
        AgentRunState.setPaused(true)
        assertTrue(AgentRunState.paused.value)

        AgentRunState.setPaused(false)
        assertFalse(AgentRunState.paused.value)
    }

    // ---- the sticky notification --------------------------------------------------

    @Test
    fun theNotificationSaysWhatTheAgentIsDoingAndOffersTheOppositeAction() {
        assumeNotificationsEnabled()

        RunStateNotification.refresh()
        val running = awaitRunState()
        assertEquals(
            "MobileClaw is running",
            running.notification.extras.getString("android.title")
        )
        assertEquals("Pause", running.notification.actions.single().title)
        assertTrue(
            "the status must be ongoing — it is the app's permanent readout",
            running.isOngoing
        )

        AgentRunState.setPaused(true)
        RunStateNotification.refresh()

        awaitUntil(message = "the notification never caught up with the paused state") {
            activeRunState()?.notification?.extras?.getString("android.title") ==
                "MobileClaw is paused"
        }
        assertEquals("Resume", awaitRunState().notification.actions.single().title)
    }

    @Test
    fun pressingPauseInTheShadeStopsTheAgent() {
        assumeNotificationsEnabled()
        RunStateNotification.refresh()

        press(awaitRunState())

        awaitUntil(message = "the Pause button did not stop the agent") { AgentRunState.isPaused }
    }

    @Test
    fun pressingResumeInTheShadeStartsItAgain() {
        assumeNotificationsEnabled()
        AgentRunState.setPaused(true)
        RunStateNotification.refresh()
        awaitUntil { activeRunState()?.notification?.actions?.single()?.title == "Resume" }

        press(awaitRunState())

        awaitUntil(message = "the Resume button did not restart the agent") { !AgentRunState.isPaused }
    }

    @Test
    fun resumingRequiresAnUnlockedDeviceAndPausingDoesNot() {
        // Pause is the safe direction. Resume restarts an unattended loop that sends
        // device text to a cloud endpoint, so it must not be reachable from the lock
        // screen of a phone left on a table — the same asymmetry as NotifConfirm's
        // Approve/Reject, and for the same reason.
        assumeNotificationsEnabled()
        assumeTrue("setAuthenticationRequired is API 31+", Build.VERSION.SDK_INT >= 31)

        RunStateNotification.refresh()
        assertFalse(
            "pausing must stay available on a locked device",
            awaitRunState().notification.actions.single().isAuthenticationRequired
        )

        AgentRunState.setPaused(true)
        RunStateNotification.refresh()
        awaitUntil { activeRunState()?.notification?.actions?.single()?.title == "Resume" }

        assertTrue(
            "resuming must require authentication",
            awaitRunState().notification.actions.single().isAuthenticationRequired
        )
    }

    @Test
    fun theStatusIsNotItselfSomethingTheAgentWouldTriage() {
        // NotifListener skips our own machinery by channel id. Asserting the channel here
        // rather than trusting the constant: if this notification ever moved to another
        // channel, the agent would start reasoning about its own status — an LLM call per
        // pause, and a notification the agent could propose dismissing.
        assumeNotificationsEnabled()
        RunStateNotification.refresh()

        assertEquals(RunStateNotification.CHANNEL_ID, awaitRunState().notification.channelId)
    }

    // ---- helpers -------------------------------------------------------------------

    private fun assumeNotificationsEnabled() = assumeTrue(
        "notifications are disabled for the app — nothing to post",
        androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    )

    private fun activeRunState(): StatusBarNotification? =
        nm.activeNotifications.firstOrNull { it.id == RunStateNotification.NOTIF_ID }

    private fun awaitRunState(): StatusBarNotification {
        awaitUntil(message = "no run-state notification was posted") { activeRunState() != null }
        return activeRunState()!!
    }

    /** Presses the button the way the shade would: by sending the action's own intent. */
    private fun press(posted: StatusBarNotification) {
        posted.notification.actions.single().actionIntent.send()
    }
}
