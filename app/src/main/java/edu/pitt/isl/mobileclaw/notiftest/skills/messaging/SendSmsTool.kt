package edu.pitt.isl.mobileclaw.notiftest.skills.messaging

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import edu.pitt.isl.mobileclaw.notiftest.ActionLogStore
import edu.pitt.isl.mobileclaw.notiftest.Capability
import edu.pitt.isl.mobileclaw.notiftest.PendingAction
import edu.pitt.isl.mobileclaw.notiftest.PermissionSignal
import edu.pitt.isl.mobileclaw.notiftest.ReasoningModule
import edu.pitt.isl.mobileclaw.notiftest.Tool
import org.json.JSONObject

/**
 * Autonomous SMS send — the one new-outbound channel that needs no
 * other app and no UI puppeteering. Kept agentic but routed through the confirm
 * gate so the user always sees the recipient + body before anything leaves the
 * device. Requires the SEND_SMS runtime permission; when it's missing the tool
 * returns a structured "capability unavailable" result and asks the app to prompt
 * for the grant, rather than crashing.
 */
class SendSmsTool(private val context: Context) : Tool {

    override val name = "send_sms"
    override val description =
        "Send a text message (SMS) directly, without opening another app. The user is asked to confirm " +
        "(recipient + message shown) before it sends. Use compose_message instead if the user should pick the app."
    override val parameters = JSONObject(
        """
        {
          "type": "object",
          "properties": {
            "to":   { "type": "string", "description": "Recipient phone number (E.164 preferred, e.g. +14125551234)." },
            "body": { "type": "string", "description": "The message text to send." }
          },
          "required": ["to", "body"]
        }
        """.trimIndent()
    )

    override suspend fun invoke(args: JSONObject): String {
        val to = args.optString("to").trim()
        val body = args.optString("body")
        if (to.isBlank()) return "I need a recipient phone number."
        if (body.isBlank()) return "I need a message to send."

        if (!Capability.SEND_SMS.isGranted(context)) {
            PermissionSignal.request(Capability.SEND_SMS)
            return Capability.unavailable(Capability.SEND_SMS)
        }

        PendingAction.propose(
            PendingAction.Proposal(
                description = "Send SMS to $to:\n\"${ReasoningModule.redact(body)}\"",
                execute = { doSend(to, body) }
            )
        )
        return "Prepared an SMS to $to — the user needs to confirm before it sends."
    }

    private fun doSend(to: String, body: String): String {
        return try {
            val sms = smsManager()
            val parts = sms.divideMessage(body)
            if (parts.size > 1) {
                sms.sendMultipartTextMessage(to, null, parts, null, null)
            } else {
                sms.sendTextMessage(to, null, body, null, null)
            }
            val result = "SMS sent to $to."
            ActionLogStore.record("send_sms", to, "send", body, result, success = true)
            result
        } catch (e: Exception) {
            val result = "SMS failed: ${e.message}"
            ActionLogStore.record("send_sms", to, "send", body, result, success = false)
            result
        }
    }

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }
}
