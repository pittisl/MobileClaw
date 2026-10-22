package edu.pitt.isl.mobileclaw.notiftest

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput

/**
 * Receives the broadcast PendingIntents wired onto the *test* notifications fired
 * by [NotificationTester]. Its only job is to make a button press observable:
 * when the agent (or the user) triggers a test button, this logs the press and
 * echoes it to the chat panel, so the click path can be verified end-to-end
 * without depending on a real third-party app.
 *
 * Real notifications carry their own app's PendingIntents — this receiver is only
 * for the synthetic test notifications, so it is not exported.
 */
class TestActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MobileClaw-TestAct"
        const val ACTION = "edu.pitt.isl.mobileclaw.notiftest.TEST_ACTION"
        const val EXTRA_LABEL = "label"
        const val REMOTE_INPUT_KEY = "key_test_reply"
        /** Notification id to cancel when this button fires; absent = don't cancel. */
        const val EXTRA_CANCEL_ID = "cancel_id"
        private const val NO_CANCEL = -1
    }

    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra(EXTRA_LABEL) ?: "(unknown)"
        val typed = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(REMOTE_INPUT_KEY)?.toString()

        val summary = if (typed != null) "$label → \"$typed\"" else label
        Log.i(TAG, "Test button fired: $summary")

        // Some buttons (Archive / Mark as read) resolve the notification — clear it.
        // autoCancel only fires on a body tap, not on action buttons, so we cancel
        // it here explicitly. These are our own test notifications, so this works.
        val cancelId = intent.getIntExtra(EXTRA_CANCEL_ID, NO_CANCEL)
        val dismissed = if (cancelId != NO_CANCEL) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(cancelId)
            true
        } else false

        // A reply is the one test button with something to act on. Echoing it to the chat
        // panel was never enough on its own: the shade is where the agent works, and a
        // reply that leaves the notification unchanged looks exactly like one that was
        // never delivered. See [NotificationTester.showEmailReplySent] for why the thread
        // it posts cannot answer itself.
        val threaded = typed?.takeIf { it.isNotBlank() }?.let {
            NotificationTester(context).showEmailReplySent(it)
            true
        } ?: false

        ChatStore.add(
            ChatMessage(
                role = ChatMessage.Role.SYSTEM,
                content = "🔘 Test button fired: $summary" + when {
                    dismissed -> " (notification dismissed)"
                    threaded -> " (thread updated — the reply now shows as sent)"
                    else -> ""
                }
            )
        )
    }
}
