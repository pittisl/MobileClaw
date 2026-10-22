package edu.pitt.isl.mobileclaw.notiftest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives the Pause / Resume press on the [RunStateNotification].
 *
 * Not exported: exported, any app on the device could resume the agent — or silence it
 * — behind the user's back, which is exactly the authority this notification exists to
 * give the *user*. The `PendingIntent`s that reach it are explicit and immutable.
 *
 * Unlike [ConfirmActionReceiver] this does its work inline, and can: the whole job is
 * one `commit()` to `SharedPreferences` and a re-post. There is no lambda to find, so a
 * cold process handles this correctly with no memory of having posted the notification.
 * [AgentRunState.init] is called first for exactly that case — the receiver may be the
 * thing that started this process.
 */
class RunStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RunStateNotification.ACTION_SET) return
        if (!intent.hasExtra(RunStateNotification.EXTRA_PAUSED)) return
        // Defaults to true: a malformed intent falls on the side of stopping the agent,
        // never on the side of starting one unattended.
        val paused = intent.getBooleanExtra(RunStateNotification.EXTRA_PAUSED, true)

        AgentRunState.init(context)
        RunStateNotification.init(context)
        Log.i(TAG, "run state set from notification: ${if (paused) "paused" else "running"}")

        if (AgentRunState.setPaused(paused)) {
            // Resuming re-syncs the store with what is actually in the shade, without
            // reasoning about any of it — see NotifListener.onRunStateResumed.
            if (!paused) NotifListener.instance?.onRunStateResumed()
        }
        // Unconditional, not only on a change: RunStateNotification.init already
        // refreshed, but the state may have changed under it in the branch above.
        RunStateNotification.refresh()
    }

    private companion object {
        const val TAG = "MobileClaw-RS"
    }
}
