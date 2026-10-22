package edu.pitt.isl.mobileclaw.notiftest.skills.notifications

import edu.pitt.isl.mobileclaw.notiftest.Capability
import edu.pitt.isl.mobileclaw.notiftest.NotifListener
import edu.pitt.isl.mobileclaw.notiftest.NotifStore
import edu.pitt.isl.mobileclaw.notiftest.PendingAction
import edu.pitt.isl.mobileclaw.notiftest.PermissionSignal
import edu.pitt.isl.mobileclaw.notiftest.ReasoningModule
import edu.pitt.isl.mobileclaw.notiftest.Tool
import org.json.JSONObject

/**
 * Action Module tools — they let the agent *act* on notifications, not just read
 * them. Both stage a [PendingAction.Proposal] so the user confirms before
 * anything irreversible happens. Sensitive text is redacted in the proposal label.
 */

/**
 * Shared availability check for the notification actuators. These have no
 * [android.content.Context]; the runtime truth for "can we act on a notification"
 * is whether the listener service is connected ([NotifListener.instance]). When it
 * isn't, fire the onboarding prompt and return the shared structured result so the
 * model reasons consistently — and so we don't stage a confirm dialog for an action
 * that can't run.
 */
private fun requireNotifAccess(): String? =
    if (NotifListener.instance == null) {
        PermissionSignal.request(Capability.NOTIFICATION_ACCESS)
        Capability.unavailable(Capability.NOTIFICATION_ACCESS)
    } else null

class DismissNotificationTool : Tool {
    override val name = "dismiss_notification"
    override val description =
        "Dismiss (clear) an active notification. Identify it by app name or a word from its title/text. The user will be asked to confirm before it is dismissed."
    override val parameters = JSONObject(
        """
        {
          "type": "object",
          "properties": {
            "query": { "type": "string", "description": "App name, package, or text identifying which notification to dismiss." }
          },
          "required": ["query"]
        }
        """.trimIndent()
    )

    override suspend fun invoke(args: JSONObject): String {
        requireNotifAccess()?.let { return it }
        val query = args.optString("query")
        val notif = NotifStore.findByHint(query)
            ?: return "No active notification matches \"$query\"."
        val label = notif.appName +
            if (notif.title.isNotBlank()) " — \"${ReasoningModule.redact(notif.title)}\"" else ""
        val key = notif.key

        PendingAction.propose(
            PendingAction.Proposal(
                description = "Dismiss notification: $label",
                execute = {
                    NotifListener.instance?.dismissByKey(key)
                        ?: run {
                            PermissionSignal.request(Capability.NOTIFICATION_ACCESS)
                            "Couldn't dismiss — notification access isn't active."
                        }
                }
            )
        )
        return "Prepared to dismiss the $label notification — the user needs to confirm."
    }
}

class ReplyNotificationTool : Tool {
    override val name = "reply_notification"
    override val description =
        "Reply inline to a messaging notification. Identify the conversation by app name or sender and provide the message. The user will be asked to confirm before it is sent."
    override val parameters = JSONObject(
        """
        {
          "type": "object",
          "properties": {
            "query":   { "type": "string", "description": "App name or sender identifying the conversation to reply to." },
            "message": { "type": "string", "description": "The reply text to send." }
          },
          "required": ["query", "message"]
        }
        """.trimIndent()
    )

    override suspend fun invoke(args: JSONObject): String {
        requireNotifAccess()?.let { return it }
        val query = args.optString("query")
        val message = args.optString("message")
        if (message.isBlank()) return "I need a message to send."
        val notif = NotifStore.findByHint(query)
            ?: return "No active notification matches \"$query\"."
        val label = notif.appName +
            if (notif.title.isNotBlank()) " · ${ReasoningModule.redact(notif.title)}" else ""
        val key = notif.key

        PendingAction.propose(
            PendingAction.Proposal(
                description = "Reply to $label: \"$message\"",
                execute = {
                    NotifListener.instance?.replyByKey(key, message)
                        ?: run {
                            PermissionSignal.request(Capability.NOTIFICATION_ACCESS)
                            "Couldn't send — notification access isn't active."
                        }
                }
            )
        )
        return "Prepared a reply to $label — the user needs to confirm before it sends."
    }
}

class ClickNotificationActionTool : Tool {
    override val name = "click_notification_action"
    override val description =
        "Press a button on an active notification (e.g. \"Mark as read\", \"Archive\", \"Confirm\"). Identify the notification by app name or a word from its title/text, and give the exact button label. Use read_notifications first to see which buttons (actions) a notification offers. For buttons that accept text (inline reply fields) you may also pass text. The user is asked to confirm before the button is pressed."
    override val parameters = JSONObject(
        """
        {
          "type": "object",
          "properties": {
            "query":  { "type": "string", "description": "App name, package, or text identifying which notification." },
            "action": { "type": "string", "description": "Exact button label to press, as listed in the notification's actions." },
            "text":   { "type": "string", "description": "Optional text to enter, only for buttons that accept inline input." }
          },
          "required": ["query", "action"]
        }
        """.trimIndent()
    )

    override suspend fun invoke(args: JSONObject): String {
        requireNotifAccess()?.let { return it }
        val query = args.optString("query")
        val actionLabel = args.optString("action")
        val text = args.optString("text").takeIf { it.isNotBlank() }
        if (actionLabel.isBlank()) return "I need the button label to press."

        val notif = NotifStore.findByHint(query)
            ?: return "No active notification matches \"$query\"."
        if (notif.actions.isEmpty()) {
            return "The ${notif.appName} notification has no buttons to press."
        }
        val match = notif.actions.firstOrNull { it.title.equals(actionLabel, ignoreCase = true) }
            ?: return "No \"$actionLabel\" button on the ${notif.appName} notification. " +
                "Available buttons: ${notif.actions.joinToString(", ") { "\"${it.title}\"" }}."

        val label = notif.appName +
            if (notif.title.isNotBlank()) " — \"${ReasoningModule.redact(notif.title)}\"" else ""
        val key = notif.key
        val buttonTitle = match.title
        // Only forward text to buttons that actually accept it (carry a RemoteInput).
        val inputText = if (match.acceptsText) text else null
        val confirmDesc = buildString {
            append("Press \"$buttonTitle\" on $label")
            if (inputText != null) append(" with text: \"${ReasoningModule.redact(inputText)}\"")
        }

        PendingAction.propose(
            PendingAction.Proposal(
                description = confirmDesc,
                execute = {
                    NotifListener.instance?.clickActionByKey(key, buttonTitle, inputText)
                        ?: run {
                            PermissionSignal.request(Capability.NOTIFICATION_ACCESS)
                            "Couldn't press the button — notification access isn't active."
                        }
                }
            )
        )
        return "Prepared to press \"$buttonTitle\" on $label — the user needs to confirm."
    }
}
