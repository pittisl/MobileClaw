package edu.pitt.isl.mobileclaw.notiftest.skills.notifications

import edu.pitt.isl.mobileclaw.notiftest.NotifStore
import edu.pitt.isl.mobileclaw.notiftest.ReasoningModule
import edu.pitt.isl.mobileclaw.notiftest.Tool
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reads the user's currently-active notifications (optionally filtered), with
 * sensitive data redacted before it leaves the device. This is the bridge that
 * connects the chat agent to the live notification stream.
 */
class ReadNotificationsTool : Tool {
    override val name = "read_notifications"
    override val description =
        "Read the user's current active notifications. Optionally filter by app package/name substring, or to messaging/chat notifications only."
    override val parameters = JSONObject(
        """
        {
          "type": "object",
          "properties": {
            "package_filter": { "type": "string", "description": "Only include notifications whose app package or name contains this text." },
            "messaging_only": { "type": "boolean", "description": "If true, only messaging/chat notifications." },
            "limit": { "type": "integer", "description": "Max notifications to return (default 25)." }
          }
        }
        """.trimIndent()
    )

    override suspend fun invoke(args: JSONObject): String {
        val pkg = args.optString("package_filter").takeIf { it.isNotBlank() }
        val messagingOnly = args.optBoolean("messaging_only", false)
        val limit = args.optInt("limit", 25).coerceIn(1, 50)

        val results = NotifStore.query(packageFilter = pkg, messagingOnly = messagingOnly, limit = limit)
        if (results.isEmpty()) return "No matching active notifications."

        val fmt = SimpleDateFormat("HH:mm", Locale.US)
        return results.joinToString("\n") { n ->
            buildString {
                append("${fmt.format(Date(n.postTimeMs))} · ${n.appName}")
                if (n.title.isNotBlank()) append(" — ${ReasoningModule.redact(n.title)}")
                val body = ReasoningModule.redact(n.text.take(160).replace('\n', ' '))
                if (body.isNotBlank()) append(": $body")
                if (n.actions.isNotEmpty()) {
                    val buttons = n.actions.joinToString(", ") {
                        if (it.acceptsText) "\"${it.title}\" (accepts text)" else "\"${it.title}\""
                    }
                    append("  [buttons: $buttons]")
                }
            }
        }
    }
}
