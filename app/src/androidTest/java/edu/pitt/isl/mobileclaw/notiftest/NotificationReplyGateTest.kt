package edu.pitt.isl.mobileclaw.notiftest

import android.content.Context
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the real approval UI with a local counter; no SMS or model is involved. */
@RunWith(AndroidJUnit4::class)
class NotificationReplyGateTest {
    @Test fun contextualReplyStillRequiresConfirmationInFullAutomationMode() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val key = AppSettings.KEY_FULL_AUTOMATION
        val saved = if (prefs.contains(key)) prefs.getBoolean(key, false) else null
        val oldPaused = AgentRunState.isPaused
        val previousSession = ChatStore.activeSessionId.value
        var testSession: String? = null
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            instrumentation.runOnMainSync { AgentRunState.setPaused(true) }
            awaitUntil { ChatStore.sessions.value.isNotEmpty() }
            instrumentation.runOnMainSync { testSession = ChatStore.newSession() }
            awaitUntil { ChatStore.sessions.value.any { it.id == testSession } }
            prefs.edit().putBoolean(key, true).commit()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            val sent = AtomicInteger()
            val draft = NotificationReplyDraft()
            draft.invoke(JSONObject().put("message", "LOCAL CONFIRMATION GATE TEST"))
            val proposal = draft.proposal("local test only") { sent.incrementAndGet(); "Local counter incremented." }!!
            assertFalse(proposal.allowFullAutomation)
            assertTrue(PendingAction.propose(proposal))
            awaitUntil(message = "No visible confirmation") {
                var shown = false
                instrumentation.runOnMainSync {
                    shown = WindowInspector.getGlobalWindowViews().any { root ->
                        root.findViewById<TextView>(android.R.id.message)?.let {
                            it.isShown && it.text.contains("LOCAL CONFIRMATION GATE TEST")
                        } == true
                    }
                }
                shown
            }
            // A visible dialog is not enough: the executor must still be untouched.
            assertEquals(0, sent.get())
            instrumentation.runOnMainSync {
                val root = WindowInspector.getGlobalWindowViews().single { root ->
                    root.findViewById<TextView>(android.R.id.message)?.text?.contains("LOCAL CONFIRMATION GATE TEST") == true
                }
                root.findViewById<Button>(android.R.id.button1).performClick()
            }
            awaitUntil(message = "Approved local callback was not invoked") { sent.get() == 1 }
        } finally {
            scenario?.close()
            val edit = prefs.edit()
            if (saved == null) edit.remove(key) else edit.putBoolean(key, saved)
            edit.commit()
            instrumentation.runOnMainSync {
                ChatStore.switchTo(previousSession)
                testSession?.let { ChatStore.deleteSession(it) }
                AgentRunState.setPaused(oldPaused)
                if (!oldPaused) NotifListener.instance?.onRunStateResumed()
            }
        }
    }
}
