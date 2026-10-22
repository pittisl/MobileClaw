package edu.pitt.isl.mobileclaw.notiftest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives the Approve / Reject presses on a [NotifConfirm] confirmation.
 *
 * Not exported, and the `PendingIntent`s that reach it are explicit and immutable — an
 * exported receiver here would be an *approve-anything* endpoint for every other app on
 * the device, which is the opposite of what the confirm gate is for.
 *
 * The work is deliberately not done here. A `BroadcastReceiver` gets ~10 seconds and
 * then its process is a candidate for death, and [PendingAction.Proposal.execute] can
 * send an SMS or run a privileged command. [NotifConfirm.resolve] hands it to a
 * long-lived scope instead.
 */
class ConfirmActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NotifConfirm.ACTION_RESOLVE) return
        val id = intent.getIntExtra(NotifConfirm.EXTRA_ID, -1)
        if (id < 0) return
        // Defaults to false: a malformed intent must fall on the side of not acting.
        val approved = intent.getBooleanExtra(NotifConfirm.EXTRA_APPROVED, false)
        Log.i(TAG, "confirmation #$id resolved: ${if (approved) "approved" else "rejected"}")
        NotifConfirm.resolve(id, approved)
    }

    private companion object {
        const val TAG = "MobileClaw-NC"
    }
}
