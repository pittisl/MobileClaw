package edu.pitt.isl.mobileclaw.notiftest.skills.notifications

import android.content.Context
import edu.pitt.isl.mobileclaw.notiftest.Capability
import edu.pitt.isl.mobileclaw.notiftest.Skill
import edu.pitt.isl.mobileclaw.notiftest.Tool

/**
 * Reading the notification shade and acting on it — the app's original reason to exist.
 *
 * Read and act ship as one skill rather than two because they are one workflow: every
 * action tool identifies its target by a hint the model got from
 * [ReadNotificationsTool], so splitting them would let a user disable half of a pair
 * and leave the model naming buttons it can no longer press.
 */
class NotificationsSkill : Skill {
    override val id = "notifications"
    override val displayName = "Notifications"
    override val summary =
        "Read your notifications, and dismiss, reply to, or press the buttons on them."
    override val capabilities = listOf(Capability.NOTIFICATION_ACCESS)

    override fun tools(context: Context): List<Tool> = listOf(
        ReadNotificationsTool(),
        DismissNotificationTool(),
        ReplyNotificationTool(),
        ClickNotificationActionTool(),
    )
}
