package edu.pitt.isl.mobileclaw.notiftest.skills.music

import android.content.Context
import edu.pitt.isl.mobileclaw.notiftest.Capability
import edu.pitt.isl.mobileclaw.notiftest.Skill
import edu.pitt.isl.mobileclaw.notiftest.Tool

/**
 * Playback control for whichever app owns the media session.
 *
 * Declares [Capability.NOTIFICATION_ACCESS] and not a media permission of its own:
 * `MediaSessionManager.getActiveSessions()` is gated on notification-listener access,
 * which is the non-obvious part worth having written down where a reader looks for it.
 */
class MusicSkill : Skill {
    override val id = "music"
    override val displayName = "Music control"
    override val summary =
        "Play, pause and skip whatever is playing, and say what track it is."
    override val capabilities = listOf(Capability.NOTIFICATION_ACCESS)

    override fun tools(context: Context): List<Tool> = listOf(MusicControlTool(context))
}
