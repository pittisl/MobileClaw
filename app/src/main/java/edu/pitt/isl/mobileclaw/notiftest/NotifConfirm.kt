package edu.pitt.isl.mobileclaw.notiftest

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The last way to answer a confirm-gate proposal: a heads-up notification with
 * **Approve** and **Reject** buttons.
 *
 * [PendingAction] uses an Activity dialog in the foreground and [OverlayConfirm]
 * when background overlays are allowed. This notification provides confirmation
 * without overlay access, subject to the app's notification permission and settings.
 *
 * ### Why this is more than "post a notification"
 *
 * [PendingAction.Proposal.execute] is a lambda. It cannot travel in a `PendingIntent`,
 * so the notification carries an **id** and the proposal stays in [pending] here. That
 * asymmetry is the whole design problem: the map dies with the process, the notification
 * does not. Everything below follows from it.
 *
 *  - **A resolved-but-unknown id is expected, not a bug.** The app can be killed while
 *    the notification sits in the shade. Approving then must say so and do nothing —
 *    never silently succeed, and never crash.
 *  - **Proposals expire.** An overlay is answered in seconds because it is in the way;
 *    a notification can sit for hours. "Delete /sdcard/DCIM" agreed to at breakfast and
 *    approved at lunch is not consent to the same thing — the context it was proposed
 *    in is gone. After [TTL_MS] the entry is dropped and the notification withdrawn.
 *  - **Swiping it away is an answer.** Dismissal drops the proposal (via the delete
 *    intent) rather than leaving it in the map for an approval that can no longer come.
 *  - **Approving requires an unlocked device** (`setAuthenticationRequired`, API 31+).
 *    Without it this path would let anyone holding the phone approve a privileged
 *    command from the lock screen, which would make the confirm gate weaker than the
 *    two paths it backs up rather than equal to them.
 *  - **Content is hidden on a secure lock screen** (`VISIBILITY_PRIVATE`). Proposal
 *    descriptions are redacted, but redaction is not the same as "fine on the lock
 *    screen of a phone on a table".
 */
object NotifConfirm {

    private const val TAG = "MobileClaw-NC"

    const val CHANNEL_ID = "action_confirm"
    const val ACTION_RESOLVE = "edu.pitt.isl.mobileclaw.notiftest.CONFIRM_RESOLVE"
    const val EXTRA_ID = "proposal_id"
    const val EXTRA_APPROVED = "approved"

    /**
     * How long an unanswered proposal stays answerable. Five minutes is chosen against
     * the failure it prevents rather than user convenience: an action the user has to
     * scroll back through the shade to find is one they have lost the context for, and
     * re-proposing is cheap — the agent can always ask again.
     */
    const val TTL_MS = 5 * 60_000L

    /** Well clear of [NotificationTester]'s 1–14. */
    private const val ID_BASE = 9000

    @Volatile
    private var appContext: Context? = null

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val main = Handler(Looper.getMainLooper())
    private val nextId = AtomicInteger(ID_BASE)

    /** Proposals awaiting an answer, by notification id. Emptied by process death. */
    private val pending = ConcurrentHashMap<Int, PendingAction.Proposal>()

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            // IMPORTANCE_HIGH so it arrives as a heads-up rather than a shade entry the
            // user finds later — a confirmation nobody sees is the drop we came from.
            NotificationChannel(CHANNEL_ID, "Action confirmations", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Approve or reject actions MobileClaw wants to take" }
        )
    }

    /**
     * Posts [proposal] as a heads-up notification. Returns false when notifications
     * can't be shown at all — no `POST_NOTIFICATIONS` grant on API 33+, or the user
     * turned them off — so [PendingAction] falls through to its fail-safe drop.
     */
    @Synchronized
    fun tryShow(proposal: PendingAction.Proposal): Boolean {
        if (!PendingAction.isCurrent(proposal)) return true
        val ctx = appContext ?: return false
        // Covers both the runtime permission and the user switching notifications off,
        // which are different causes with the same consequence: nobody would see it.
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return false

        val id = nextId.incrementAndGet()
        pending[id] = proposal

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Confirm action")
            .setContentText(proposal.description)
            // The description can run past one line, and truncating the thing being
            // agreed to is the one place brevity is not worth it.
            .setStyle(NotificationCompat.BigTextStyle().bigText(proposal.description))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            // Belt and braces with expire(): the platform withdraws it even if this
            // process is gone by then, so a stale confirmation doesn't sit in the shade.
            .setTimeoutAfter(TTL_MS)
            .setDeleteIntent(resolveIntent(ctx, id, approved = false, requestOffset = 2))
            .addAction(rejectAction(ctx, id))
            .addAction(approveAction(ctx, id))
            .build()

        return try {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(id, notification)
            main.postDelayed({ expire(id) }, TTL_MS)
            true
        } catch (e: Exception) {
            // e.g. the permission is revoked between the check above and the post.
            Log.w(TAG, "Failed to post confirmation; dropping proposal", e)
            pending.remove(id)
            false
        }
    }

    private fun approveAction(ctx: Context, id: Int): NotificationCompat.Action =
        NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send, "Approve",
            resolveIntent(ctx, id, approved = true, requestOffset = 0)
        )
            // Anyone can reach the shade of a locked phone. Approving is the half of
            // this that acts, so it is the half that has to prove who is asking.
            .setAuthenticationRequired(true)
            .build()

    private fun rejectAction(ctx: Context, id: Int): NotificationCompat.Action =
        NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel, "Reject",
            resolveIntent(ctx, id, approved = false, requestOffset = 1)
        ).build()

    /**
     * An explicit intent to our own non-exported receiver, so no other app can send it.
     * [requestOffset] keeps the three intents of one proposal distinct — same request
     * code would have them overwrite each other under `FLAG_UPDATE_CURRENT`, and the
     * Reject button would run the Approve extras.
     */
    private fun resolveIntent(ctx: Context, id: Int, approved: Boolean, requestOffset: Int): PendingIntent {
        val intent = Intent(ctx, ConfirmActionReceiver::class.java).apply {
            action = ACTION_RESOLVE
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_APPROVED, approved)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(ctx, id * 4 + requestOffset, intent, flags)
    }

    /**
     * The answer arriving. Called from [ConfirmActionReceiver]; also the point where
     * "the app was restarted" has to be told apart from "the user said no", because
     * they look identical from here — an id with nothing behind it.
     */
    internal fun resolve(id: Int, approved: Boolean) {
        cancel(id)
        val proposal = pending.remove(id)

        if (proposal == null) {
            // Not a bug: the process was killed, or it already expired, or a second tap
            // arrived. Recorded rather than swallowed, because from the user's side they
            // pressed Approve and nothing happened, and the log is where they find out why.
            ActionLogStore.record(
                appName = "notification", key = "-",
                actionTitle = "(confirmation #$id — no longer in memory)",
                suppliedText = null,
                result = "Not executed: the app restarted or the confirmation expired or was cleared by demo reset before it was answered.",
                success = false
            )
            return
        }

        if (!approved) {
            PendingAction.decline(proposal)
            ActionLogStore.record(
                appName = "notification", key = "-", actionTitle = proposal.description,
                suppliedText = null, result = "Rejected by user (notification).", success = false
            )
            return
        }

        scope.launch {
            PendingAction.executeAndPublish(proposal)
        }
    }

    /** Withdraws only action confirmations, including ones left behind by a dead process. */
    @Synchronized
    internal fun resetForDemo() {
        val ids = pending.keys.toList()
        pending.clear()
        main.removeCallbacksAndMessages(null)
        ids.forEach(::cancel)
        val ctx = appContext ?: return
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.activeNotifications.filter { it.notification.channelId == CHANNEL_ID }
                .forEach { nm.cancel(it.tag, it.id) }
        }.onFailure { Log.w(TAG, "Failed to withdraw old confirmations", it) }
    }

    /** Nobody answered in time. Same outcome as the fail-safe drop, and recorded as one. */
    private fun expire(id: Int) {
        val proposal = pending.remove(id) ?: return
        cancel(id)
        ActionLogStore.record(
            appName = "notification", key = "-", actionTitle = proposal.description,
            suppliedText = null,
            result = "Expired unanswered after ${TTL_MS / 60_000} minutes — not executed.",
            success = false
        )
    }

    private fun cancel(id: Int) {
        val ctx = appContext ?: return
        runCatching {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id)
        }
    }

    /** For tests: how many proposals are still awaiting an answer. */
    internal val awaitingCount: Int get() = pending.size
}
