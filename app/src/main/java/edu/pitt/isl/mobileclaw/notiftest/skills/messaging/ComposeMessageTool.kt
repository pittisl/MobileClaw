package edu.pitt.isl.mobileclaw.notiftest.skills.messaging

import android.content.Context
import android.content.Intent
import android.net.Uri
import edu.pitt.isl.mobileclaw.notiftest.ActionLogStore
import edu.pitt.isl.mobileclaw.notiftest.Tool
import java.net.URLEncoder
import org.json.JSONObject

/**
 * The primary proactive-outbound tool. A smart router that opens the
 * *right* app pre-filled with a drafted message; the user reviews and presses
 * send. Deliberately not fully autonomous — every new outbound conversation ends
 * with a human tap, so this needs no runtime permission and no confirm gate (it
 * launches compose UIs, it does not send). The launch is still recorded in
 * [ActionLogStore] with redacted content.
 */
class ComposeMessageTool(private val context: Context) : Tool {

    override val name = "compose_message"
    override val description =
        "Open the right messaging app PRE-FILLED with a drafted message for the user to review and send. " +
        "Use for starting a new outbound message (email, SMS, WhatsApp, Telegram) — the user always taps send themselves. " +
        "For sending an SMS without opening another app, use send_sms instead."
    override val parameters = JSONObject(
        """
        {
          "type": "object",
          "properties": {
            "channel":       { "type": "string", "enum": ["email", "sms", "whatsapp", "telegram", "share"],
                               "description": "Delivery channel. Omit to infer from 'to' (email address -> email, phone number -> sms, else share sheet)." },
            "to":            { "type": "string", "description": "Recipient: email address, phone number (E.164 preferred), or @username for Telegram." },
            "subject":       { "type": "string", "description": "Subject line (email only)." },
            "body":          { "type": "string", "description": "The drafted message body." },
            "attachment_uri":{ "type": "string", "description": "Optional content:// URI to attach (email/share)." }
          },
          "required": ["body"]
        }
        """.trimIndent()
    )

    override suspend fun invoke(args: JSONObject): String {
        val body = args.optString("body")
        if (body.isBlank()) return "I need a message body to draft."
        val to = args.optString("to").takeIf { it.isNotBlank() }
        val subject = args.optString("subject").takeIf { it.isNotBlank() }
        val attachment = args.optString("attachment_uri").takeIf { it.isNotBlank() }?.let(Uri::parse)
        val channel = args.optString("channel").lowercase().takeIf { it.isNotBlank() }
            ?: infer(to)

        val intent = when (channel) {
            "email"    -> emailIntent(to, subject, body, attachment)
            "sms"      -> smsIntent(to, body)
            "whatsapp" -> deepLinkIntent(whatsAppUrl(to, body))
            "telegram" -> deepLinkIntent(telegramUrl(body))
            else       -> shareIntent(body, attachment) // "share" / fallback
        }

        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            log(channel, to, body, "Opened $channel composer.", success = true)
            "Opened the ${channelLabel(channel)} composer${to?.let { " for $it" } ?: ""}, " +
                "pre-filled and ready for the user to review and send. Nothing has been sent yet."
        } catch (e: android.content.ActivityNotFoundException) {
            log(channel, to, body, "No app for $channel.", success = false)
            "No app on this device can handle a $channel message. Try a different channel " +
                "(e.g. \"share\" to use the system share sheet)."
        }
    }

    private fun infer(to: String?): String = when {
        to == null -> "share"
        to.contains("@") && to.contains(".") && !to.startsWith("@") -> "email"
        to.count { it.isDigit() } >= 7 -> "sms"
        else -> "share"
    }

    private fun emailIntent(to: String?, subject: String?, body: String, attachment: Uri?): Intent {
        // ACTION_SENDTO with a mailto: URI filters the chooser to email apps only.
        val base = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${to.orEmpty()}")).apply {
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            putExtra(Intent.EXTRA_TEXT, body)
        }
        if (attachment == null) return base
        // Attachments need ACTION_SEND; keep the email-only targeting via selector.
        return Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            to?.let { putExtra(Intent.EXTRA_EMAIL, arrayOf(it)) }
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            putExtra(Intent.EXTRA_TEXT, body)
            putExtra(Intent.EXTRA_STREAM, attachment)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        }
    }

    private fun smsIntent(to: String?, body: String): Intent =
        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${to.orEmpty()}")).apply {
            putExtra("sms_body", body)
        }

    private fun whatsAppUrl(to: String?, body: String): String {
        val number = to?.filter { it.isDigit() }.orEmpty()
        return "https://wa.me/$number?text=${enc(body)}"
    }

    // Telegram has no reliable public deep-link to pre-fill a *specific* chat, so
    // open its share picker pre-filled with the text; the user picks the chat.
    private fun telegramUrl(body: String): String =
        "https://t.me/share/url?url=${enc(body)}"

    private fun deepLinkIntent(url: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(url))

    private fun shareIntent(body: String, attachment: Uri?): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = if (attachment != null) "*/*" else "text/plain"
            putExtra(Intent.EXTRA_TEXT, body)
            attachment?.let {
                putExtra(Intent.EXTRA_STREAM, it)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        return Intent.createChooser(send, "Share via")
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun channelLabel(channel: String): String = when (channel) {
        "email" -> "email"; "sms" -> "messaging"; "whatsapp" -> "WhatsApp"
        "telegram" -> "Telegram"; else -> "share-sheet"
    }

    private fun log(channel: String, to: String?, body: String, result: String, success: Boolean) {
        ActionLogStore.record(
            appName = "compose_message/$channel",
            key = to ?: "-",
            actionTitle = "compose",
            suppliedText = body,           // redacted inside ActionLogStore.record
            result = result,
            success = success
        )
    }
}
