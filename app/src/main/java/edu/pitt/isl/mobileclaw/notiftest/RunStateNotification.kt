package edu.pitt.isl.mobileclaw.notiftest

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * The agent's status in the shade, with the button that stops it.
 *
 * The background triage loop is the one part of this app that runs while the app is
 * closed, and it was the one part with no surface while the app is closed. This is an
 * ongoing notification saying whether the agent is working, carrying Pause or Resume.
 *
 * ### Why this is not a foreground service
 *
 * It looks like one and isn't. What keeps this process alive is [NotifListener], which
 * the system binds and keeps bound for as long as notification access is granted; a
 * foreground service would add a second lifetime to reason about and buy nothing. The
 * consequence, accepted rather than worked around, is that on Android 14+ the user can
 * swipe an ongoing non-FGS notification away. It comes back on the next state change and
 * on app start, and taking that choice away is not worth a service.
 *
 * ### Why this one is simple and [NotifConfirm] is not
 *
 * Both put a button in the shade, and the difference is what the button carries.
 * `PendingAction.Proposal.execute` is a **lambda**, which cannot ride in a
 * `PendingIntent` — so [NotifConfirm] carries an id, keeps the proposal in a map that
 * dies with the process, and everything awkward about it (the TTL, the delete intent,
 * "resolved an id nobody remembers" being a normal outcome) follows from that.
 *
 * This button carries **state**: it flips one persisted flag. A cold process handles it
 * correctly with no memory of having posted the notification, so there is nothing to
 * expire, nothing to lose, and no failure mode to record.
 */
object RunStateNotification {

    private const val TAG = "MobileClaw-RS"

    const val CHANNEL_ID = "agent_run_state"
    const val ACTION_SET = "edu.pitt.isl.mobileclaw.notiftest.SET_RUN_STATE"
    const val EXTRA_PAUSED = "paused"

    /** One notification, always the same id, so posting again updates in place.
     *  Clear of [NotificationTester]'s 1–14 and [NotifConfirm]'s 9000+. */
    const val NOTIF_ID = 8000

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            // LOW: this is status, not an alert. It must never make a sound or a
            // heads-up — it is posted on every state change and on every app start.
            NotificationChannel(CHANNEL_ID, "Agent status", NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = "Shows whether MobileClaw is watching notifications, with a pause button"
                    setShowBadge(false)
                }
        )
        refresh()
    }

    /**
     * Posts or updates the notification to match the current state. Called on every
     * state change, on app start, and when the listener connects or disconnects — the
     * last because "running" and "running but notification access is off" are different
     * answers and reporting the second as the first would be a lie in the one place
     * whose whole job is to say what the agent is doing.
     */
    fun refresh() {
        val ctx = appContext ?: return
        // No POST_NOTIFICATIONS grant, or the user turned notifications off. Nothing to
        // do — the in-app status strip is the other half of this feature for exactly
        // this case.
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return

        val paused = AgentRunState.isPaused
        val connected = NotifListener.instance != null

        val title = if (paused) "MobileClaw is paused" else "MobileClaw is running"
        // Ordered by what is actually stopping the agent, nearest cause first: a pause is
        // the user's own choice and outranks everything, then notification access (with it
        // off nothing is captured, so the key is moot), then whether an LLM is reachable.
        // Reporting any of these as "watching and triaging" is the same class of lie as an
        // audit view that silently truncates — the app looks like it is working.
        val text = when {
            paused -> "Notifications are not being captured or sent to the assistant."
            !connected -> "Waiting for notification access — nothing is being captured yet."
            LlmConfig.status.value == LlmConfig.Status.NOT_CONFIGURED ->
                "Capturing notifications, but no model service is configured — none of them " +
                    "are being triaged."
            LlmConfig.status.value == LlmConfig.Status.REJECTED ->
                "Capturing notifications, but the LLM API key was rejected — none of them " +
                    "are being triaged."
            else -> "Watching notifications and triaging them with the assistant."
        }

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(
                if (paused) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent(ctx))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            // No timestamp: it would read as "something happened at 14:32" when the only
            // thing that happened is that the app started.
            .setShowWhen(false)
            .addAction(toggleAction(ctx, paused))
            .build()

        runCatching {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, notification)
        }.onFailure { Log.w(TAG, "Could not post the run-state notification", it) }
    }

    /** Withdraws it. Only for tests — in the app this notification is meant to stay up. */
    internal fun cancel() {
        val ctx = appContext ?: return
        runCatching {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NOTIF_ID)
        }
    }

    private fun toggleAction(ctx: Context, paused: Boolean): NotificationCompat.Action {
        val builder = NotificationCompat.Action.Builder(
            if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
            if (paused) "Resume" else "Pause",
            setStateIntent(ctx, pause = !paused)
        )
        // Pause is the safe direction and stays available on a locked phone; resuming
        // restarts an unattended loop that sends device text to a cloud endpoint, so it
        // proves who is asking first. Same asymmetry as NotifConfirm's Approve/Reject.
        if (paused) builder.setAuthenticationRequired(true)
        return builder.build()
    }

    /** Explicit intent to our own non-exported receiver, so no other app can send it. */
    private fun setStateIntent(ctx: Context, pause: Boolean): PendingIntent {
        val intent = Intent(ctx, RunStateReceiver::class.java).apply {
            action = ACTION_SET
            putExtra(EXTRA_PAUSED, pause)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        // One request code per direction. `Intent.filterEquals` ignores extras, so a
        // shared code would collapse both directions into a single PendingIntent that
        // FLAG_UPDATE_CURRENT rewrites on every refresh. That happens to stay correct
        // today — there is one notification and it is re-posted on every state change, so
        // the live extras always match the displayed button — but it is correct by
        // coincidence, and a second holder of either intent would break it. Two codes
        // cost nothing and remove the reasoning.
        return PendingIntent.getBroadcast(ctx, if (pause) REQ_PAUSE else REQ_RESUME, intent, flags)
    }

    private fun openAppIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(ctx, REQ_OPEN, intent, flags)
    }

    private const val REQ_PAUSE = 8001
    private const val REQ_RESUME = 8002
    private const val REQ_OPEN = 8003
}
