package edu.pitt.isl.mobileclaw.notiftest.skills.music

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import edu.pitt.isl.mobileclaw.notiftest.Capability
import edu.pitt.isl.mobileclaw.notiftest.NotifListener
import edu.pitt.isl.mobileclaw.notiftest.PermissionSignal
import edu.pitt.isl.mobileclaw.notiftest.Tool
import org.json.JSONObject

/**
 * Controls whatever app currently owns the device's media session (Spotify,
 * YouTube Music, etc.). Reuses the app's existing Notification Listener access —
 * the same permission `MediaSessionManager.getActiveSessions()` requires — so no
 * new permission is needed. (Jash's "Music control" skill.)
 */
class MusicControlTool(private val context: Context) : Tool {

    override val name = "music_control"
    override val description =
        "Control media playback on the device (play, pause, skip next/previous) or get the currently playing track."
    override val parameters = JSONObject(
        """
        {
          "type": "object",
          "properties": {
            "action": {
              "type": "string",
              "enum": ["play", "pause", "next", "previous", "now_playing"],
              "description": "The playback action to perform."
            }
          },
          "required": ["action"]
        }
        """.trimIndent()
    )

    override suspend fun invoke(args: JSONObject): String {
        val action = args.optString("action").lowercase()

        // Media control rides on the same Notification-access grant. Check it up
        // front so a missing grant returns the shared structured result (and fires
        // the onboarding prompt) rather than surfacing as a SecurityException.
        if (!Capability.NOTIFICATION_ACCESS.isGranted(context)) {
            PermissionSignal.request(Capability.NOTIFICATION_ACCESS)
            return Capability.unavailable(Capability.NOTIFICATION_ACCESS)
        }

        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val listener = ComponentName(context, NotifListener::class.java)

        val controller = try {
            msm.getActiveSessions(listener).firstOrNull()
        } catch (e: SecurityException) {
            // Belt-and-suspenders: access revoked between the check above and now.
            PermissionSignal.request(Capability.NOTIFICATION_ACCESS)
            return Capability.unavailable(Capability.NOTIFICATION_ACCESS)
        } ?: return "Nothing is playing right now (no active media session). Note: some apps (e.g. YouTube Shorts) don't expose media controls — try Spotify or YouTube Music."

        val controls = controller.transportControls
        return when (action) {
            "play"     -> { controls.play(); "▶️ Playing." }
            "pause"    -> { controls.pause(); "⏸️ Paused." }
            "next"     -> { controls.skipToNext(); "⏭️ Skipped to next." }
            "previous" -> { controls.skipToPrevious(); "⏮️ Skipped to previous." }
            "now_playing" -> {
                val md = controller.metadata
                val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
                val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                when {
                    title != null && artist != null -> "Now playing: \"$title\" by $artist (${controller.packageName})."
                    title != null                   -> "Now playing: \"$title\" (${controller.packageName})."
                    else                             -> "Something is playing on ${controller.packageName} (no track metadata)."
                }
            }
            else -> "Unknown action \"$action\". Use play, pause, next, previous, or now_playing."
        }
    }
}
