package edu.pitt.isl.mobileclaw.notiftest

import org.json.JSONObject

/**
 * A semantic alternative to a text reply, chosen by the model, not by keyword routing.
 * This turn-local tool only marks actual search results for review. It has no selection,
 * recipient or sending API and is deliberately absent from the global tool registry.
 */
internal class NotificationPhotoSuggestion(
    private val photos: PhotoTurn,
    private val draft: NotificationReplyDraft?,
) : Tool {
    override val name = "suggest_photo_share"
    override val description = "Suggest sharing photos in response to the original notification. " +
        "Use this when the sender requests photos or sharing photos is the useful next step, " +
        "not merely because an image contains information needed for a text answer. " +
        "First use search_files for the requested subject and capture date. " +
        "This shows only the actual image candidates returned by that search; it never selects " +
        "a photo or recipient, stages an MMS, or sends anything. The user must preview and select " +
        "a photo, then request sharing and confirm the image and recipient. " +
        "Use this also after an unsuccessful search to explain that no candidates are available, " +
        "rather than preparing an irrelevant text reply. It replaces any text-reply draft."
    override val parameters = JSONObject("""{"type":"object","properties":{},"additionalProperties":false}""")

    private var searched = false
    var requested = false
        private set
    var resolved = false
        private set

    /** The newest search replaces older candidates, including when it fails before returning hits. */
    fun tracking(reader: Tool): Tool {
        val tracked = draft?.tracking(reader) ?: reader
        return object : Tool by tracked {
            override fun onInvocationStarted() {
                if (reader.name == "search_files") {
                    photos.showCandidates(emptyList())
                    searched = false
                    resolved = false
                }
                tracked.onInvocationStarted()
            }

            override suspend fun invoke(args: JSONObject): String = invokeWithReceipt(args).text

            override suspend fun invokeWithReceipt(args: JSONObject): ToolInvocation {
                // Direct callers get the same invalidation as the agent loop's pre-parse hook.
                onInvocationStarted()
                return tracked.invokeWithReceipt(args).also {
                    if (reader.name == "search_files" && args.optString("query").isNotBlank()) searched = true
                }
            }
        }
    }

    /** A later text draft supersedes the photo branch; the last valid preparation wins. */
    fun textReplyTool(): Tool? = draft?.let { text ->
        object : Tool by text {
            override fun onInvocationStarted() {
                requested = false
                resolved = false
                text.onInvocationStarted()
            }
            override suspend fun invoke(args: JSONObject): String {
                onInvocationStarted()
                return text.invoke(args)
            }
            override suspend fun invokeWithReceipt(args: JSONObject): ToolInvocation = ToolInvocation.Text(invoke(args))
        }
    }

    override fun onInvocationStarted() {
        requested = true
        resolved = false
        draft?.onInvocationStarted()
    }

    override suspend fun invoke(args: JSONObject): String {
        onInvocationStarted()
        if (args.length() != 0) return "Suggestion not prepared: this tool takes no arguments; candidates come only from search_files."
        if (!searched) return "Suggestion not prepared: search_files must return for this request first."
        resolved = true
        return if (photos.candidates.isEmpty())
            "No photo candidates are available to review. Explain the search result or access limitation; do not invent matches. Nothing is selected or sent."
        else "${photos.candidates.size} actual image candidate(s) will be shown for review. " +
            "Ask the user to preview one and tap Use this photo, then request sharing. " +
            "No photo or recipient is selected and no MMS has been prepared or sent. Do not draft a separate text reply."
    }

    fun completionReminder(): String? = when {
        resolved -> null
        requested -> "Complete the photo suggestion: search_files must return, then call suggest_photo_share with no arguments. " +
            "Explain missing matches or access when necessary; do not substitute a text reply for requested photos."
        else -> draft?.completionReminder()?.plus(
            " If the useful response is photo sharing, search for the actual images and call suggest_photo_share instead of drafting a text reply.")
    }

    fun nextRequiredTool(): String? = when {
        resolved -> null
        requested -> if (searched) name else "search_files"
        // Search results (including zero matches) do not establish sharing intent:
        // let the model choose the photo or text branch rather than forcing a reply.
        searched -> null
        else -> draft?.nextRequiredTool()
    }

    fun render(base: ChatMessage?, notif: CapturedNotif): ChatMessage {
        val instruction = if (photos.candidates.isEmpty())
            "No photo candidates are available to review. Nothing has been selected or sent."
        else "Preview a photo, tap **Use this photo**, then ask me to share it."
        val source = notif.title.ifBlank { notif.appName }
        val content = base?.content?.takeIf { it.isNotBlank() }
            ?: "**Photo request from ${ReasoningModule.redact(source)}**"
        return (base ?: ChatMessage(ChatMessage.Role.ASSISTANT, content))
            .copy(content = "$content\n\n$instruction",
                // A model-generated send chip would run before the required preview.
                actions = emptyList(), photos = photos.candidates)
    }
}
