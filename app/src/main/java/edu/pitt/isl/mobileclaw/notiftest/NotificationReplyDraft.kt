package edu.pitt.isl.mobileclaw.notiftest

import org.json.JSONObject

/** A turn-local draft buffer, never a sender and never part of the global tool catalog. */
class NotificationReplyDraft(private val requiredReaders: Set<String> = emptySet()) : Tool {
    private val completedReaders = mutableSetOf<String>()

    /** Mark actual reader returns on the invocation coroutine, never buffered UI events. */
    fun tracking(reader: Tool): Tool = object : Tool by reader {
        override suspend fun invokeWithReceipt(args: JSONObject): ToolInvocation =
            reader.invokeWithReceipt(args).also {
                // Search rejects an empty query before touching its index. That
                // parameter rejection is not a source lookup (unlike no matches).
                if (reader.name != "search_files" || args.optString("query").isNotBlank())
                    completedReaders += reader.name
            }
    }

    private fun missingReaders(): Set<String> = requiredReaders - completedReaders

    /** Only used after the model tries to finish without resolving this draft. */
    fun nextRequiredTool(): String? = if (resolved) null else missingReaders().firstOrNull() ?: name
    override val name = "draft_notification_reply"
    override val description = "Prepare a concise reply to the original incoming notification for the user's review. " +
        "First read the sources needed to answer ALL its questions. Do not silently omit a question. " +
        "For timing comparisons include both the user's calendar plan and the rule/deadline from its source. " +
        "Use only verified facts; state what remains unknown. " +
        "This only saves a draft: it does not send, select a recipient, or approve anything. " +
        "The app will ask the user to confirm this exact text after this turn finishes. " +
        "If a useful reply cannot be prepared, supply hold_reason instead of message and explain why." +
        if (requiredReaders.isEmpty()) "" else " For this notification, source lookups with " +
            requiredReaders.joinToString() + " must return before a reply draft will be accepted."
    override val parameters = JSONObject("""{
        "type":"object","properties":{
          "message":{"type":"string","description":"The complete reply text for review."},
          "hold_reason":{"type":"string","description":"Why no reply should be proposed, if necessary facts cannot be verified."}
        },"additionalProperties":false
    }""")

    var message: String? = null
        private set
    private var resolved = false

    /** Called before JSON parsing too, so a malformed revision invalidates its predecessor. */
    override fun onInvocationStarted() {
        message = null
        resolved = false
    }

    override suspend fun invoke(args: JSONObject): String {
        // A malformed revision must not leave a previous draft looking current.
        message = null
        resolved = false
        val keys = args.keys().asSequence().toSet()
        if (keys == setOf("hold_reason")) {
            val reason = (args.opt("hold_reason") as? String)?.trim()
            if (reason.isNullOrEmpty() || reason.length > 4_000) return "Draft not saved: provide a short non-empty hold_reason."
            resolved = true
            return "Reply withheld: $reason. Explain the limitation to the user; nothing will be sent or proposed."
        }
        if (keys != setOf("message")) {
            return "Draft not saved: supply only message, or only hold_reason. The recipient is fixed to the original notification."
        }
        val text = (args.opt("message") as? String)?.trim()
        if (text.isNullOrEmpty() || text.length > 4_000) return "Draft not saved: message must contain 1–4000 characters."
        if (missingReaders().isNotEmpty()) return "Draft not saved: the notification explicitly requires these sources, " +
            "which have not returned yet: ${missingReaders().joinToString()}. Read them before drafting. " +
            "If access or evidence is unavailable, state the limitation or use hold_reason."
        message = text
        resolved = true
        return "Draft saved for user review. Nothing has been sent. Do not ask the user to request a draft again."
    }

    fun completionReminder(): String? = if (resolved) null else
        "This notification needs a reply decision. Do not finish by offering a drafting chip. " +
            (if (missingReaders().isNotEmpty()) "Required sources still unread: ${missingReaders().joinToString()}. " else "") +
            "Use the available readers to resolve all questions, then call draft_notification_reply with message. " +
            "If essential facts cannot be verified, call it with hold_reason instead. Nothing is sent without user confirmation."

    /** Snapshot the text only after a completed turn; later tool calls cannot alter an approval. */
    fun proposal(label: String, send: suspend (String) -> String): PendingAction.Proposal? {
        val text = message ?: return null
        return PendingAction.Proposal(
            description = "Reply to $label: \"$text\"",
            allowFullAutomation = false,
            execute = { send(text) },
        )
    }

    companion object {
        fun eligible(notif: CapturedNotif, decision: ActionDecision): Boolean =
            decision.action == ActionDecision.Action.REPLY && notif.actions.any { it.acceptsText }
    }
}
