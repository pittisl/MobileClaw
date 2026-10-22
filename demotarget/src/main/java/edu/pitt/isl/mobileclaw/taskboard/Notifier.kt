package edu.pitt.isl.mobileclaw.taskboard

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Posts the notification `postNotification` asks for. A notification is the most
 * visible thing another app can do at a booth, and it is also the thing MobileClaw's
 * *other* loop captures — so a function call from the chat side can produce an event the
 * triage side then sees, which is the round trip the demo is about.
 */
object Notifier {

    private const val CHANNEL = "taskboard"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "TaskBoard", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    /** True when the app may post; on API 33+ that is a runtime permission the screen asks for once. */
    fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Posts and returns the notification id, so the caller can name what it did. */
    fun post(context: Context, title: String, text: String): Int {
        ensureChannel(context)
        val id = (System.currentTimeMillis() % 100_000).toInt()
        val open = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
        return id
    }
}
