package edu.pitt.isl.mobileclaw.notiftest.skills.messaging

import android.content.Context
import edu.pitt.isl.mobileclaw.notiftest.Capability
import edu.pitt.isl.mobileclaw.notiftest.Skill
import edu.pitt.isl.mobileclaw.notiftest.Tool

/**
 * Sending messages, and the contact lookup that has to happen first.
 *
 * [LookupContactTool] belongs here rather than with the read-only context tools:
 * "who is Mom" is only asked in order to write to her, and the two permissions are
 * granted together in practice.
 */
class MessagingSkill : Skill {
    override val id = "messaging"
    override val displayName = "Messaging"
    override val summary =
        "Look people up in your contacts and message them — either pre-filled in the " +
            "app of your choice, or sent as an SMS or selected photo MMS after you confirm."
    override val capabilities = listOf(Capability.READ_CONTACTS, Capability.SEND_SMS)

    override fun tools(context: Context): List<Tool> = listOf(
        LookupContactTool(context),
        ComposeMessageTool(context),
        SendSmsTool(context),
        SendPhotoTool(context),
    )
}
