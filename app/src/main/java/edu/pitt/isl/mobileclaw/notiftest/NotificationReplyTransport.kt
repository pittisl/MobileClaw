package edu.pitt.isl.mobileclaw.notiftest

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/** Dispatch only after the existing reply confirmation gate has approved the text. */
internal object NotificationReplyTransport {
    const val SUBMITTED = "Reply handed to the messaging app. Delivery is not confirmed."

    internal fun options(isActivity: Boolean): ActivityOptions? {
        if (!isActivity || Build.VERSION.SDK_INT < 34) return null
        return ActivityOptions.makeBasic().apply {
            setPendingIntentBackgroundActivityStartMode(
                if (Build.VERSION.SDK_INT >= 36) ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                else ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            )
        }
    }

    fun send(context: Context, pendingIntent: PendingIntent, fillIn: Intent) {
        val launchOptions = if (Build.VERSION.SDK_INT >= 34) options(pendingIntent.isActivity)?.toBundle() else null
        pendingIntent.send(context, 0, fillIn, null, null, null, launchOptions)
    }
}
