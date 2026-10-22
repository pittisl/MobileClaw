package edu.pitt.isl.mobileclaw.notiftest

import android.util.Log
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Readable decision and tool traces for a workstation connected through adb.
 *
 * The dedicated `MobileClaw-TRACE` tag includes the model's prompt and execution
 * events. [dump] can replay persisted chat and triage records without repeating the
 * original notification or model request. This is an execution trace, not access to
 * a model's hidden reasoning.
 *
 * Every output is redacted at this boundary, with no raw mode. Inputs cannot be
 * assumed clean: user-typed text and user-attested memories may legitimately be
 * stored unredacted, while logcat is readable by anyone with adb access.
 *
 * Live tracing is off by default because full prompts can fill logcat's ring buffer.
 * [enabled] gates live output; replay reads existing records and does not require
 * live tracing to have been enabled in advance.
 */
object ReasoningTrace {

    const val TAG = "MobileClaw-TRACE"

    /**
     * Logcat drops an entry beyond roughly 4 KB. Long lines are hard-wrapped rather than
     * truncated: a system prompt arrives as one very long line, and silently losing its
     * tail would make the trace lie about what the model saw.
     */
    private const val MAX_LINE = 900

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    /**
     * Numbers chat turns so each line identifies its turn. Events can arrive from
     * multiple threads while triage, background follow-ups and foreground chat overlap.
     * The identifier lets readers and `tools/trace_viewer` associate tool calls with the
     * correct turn. It is process-local and restarts at 1 when the app starts.
     */
    private val turnSeq = java.util.concurrent.atomic.AtomicInteger()

    /** Live tracing follows the Settings switch; replay ignores it. See the class note. */
    val enabled: Boolean get() = AppSettings.reasoningTrace

    // ---- live: the foreground chat agent -------------------------------------------

    /**
     * The context a turn is about to run on. Called before the request, because a turn
     * that fails mid-stream is exactly the one whose prompt you want to see.
     */
    fun chatTurnStart(
        sessionTitle: String,
        systemPrompt: String,
        history: List<ChatMessage>,
        toolCount: Int,
        droppedTurns: Int
    ): Int {
        val turn = turnSeq.incrementAndGet()
        if (!enabled) return turn
        emit(StringBuilder(formatChatTurnStart(
            sessionTitle, systemPrompt, history, toolCount, droppedTurns, turn)))
        return turn
    }

    /** Pure half of [chatTurnStart]; see [formatTriage] for why these are split out. */
    internal fun formatChatTurnStart(
        sessionTitle: String,
        systemPrompt: String,
        history: List<ChatMessage>,
        toolCount: Int,
        droppedTurns: Int,
        turn: Int = 0,
    ): String {
        val b = StringBuilder()
        b.appendLine(rule("CHAT TURN #$turn", sessionTitle))
        b.appendLine("▸ SYSTEM PROMPT  (${systemPrompt.length} chars, $toolCount tools offered)")
        b.appendLine(indent(ReasoningModule.redact(systemPrompt)))
        b.appendLine("▸ HISTORY  ${history.size} turn(s) kept, $droppedTurns dropped")
        history.forEach { m ->
            val who = if (m.role == ChatMessage.Role.USER) "USER     " else "ASSISTANT"
            b.appendLine(indent("$who  ${ReasoningModule.redact(m.content)}"))
        }
        return b.toString()
    }

    /** A tool the model asked for, with the arguments it chose. */
    fun toolCall(turn: Int, name: String, argsJson: String) {
        if (!enabled) return
        emit(StringBuilder(formatToolCall(turn, name, argsJson)))
    }

    /** Pure half of [toolCall]. */
    internal fun formatToolCall(turn: Int, name: String, argsJson: String): String =
        "▸ TOOL CALL  #$turn  $name  ${ReasoningModule.redact(argsJson)}"


    /** What the tool handed back — the whole thing, not the 160 chars `MobileClaw-AI` logs. */
    fun toolResult(turn: Int, name: String, result: String) {
        if (!enabled) return
        emit(StringBuilder(formatToolResult(name, result, turn)))
    }

    /** Pure half of [toolResult]. */
    internal fun formatToolResult(name: String, result: String, turn: Int = 0): String = buildString {
        appendLine("▸ TOOL RESULT  #$turn  $name")
        appendLine(indent(ReasoningModule.redact(result)))
    }

    /** The answer, and what it cost. */
    fun chatTurnEnd(turn: Int, finishReason: String?, text: String, promptTokens: Int, completionTokens: Int) {
        if (!enabled) return
        emit(StringBuilder(formatChatTurnEnd(finishReason, text, promptTokens, completionTokens, turn)))
    }

    /** Pure half of [chatTurnEnd]. */
    internal fun formatChatTurnEnd(
        finishReason: String?, text: String, promptTokens: Int, completionTokens: Int, turn: Int = 0
    ): String {
        val b = StringBuilder()
        val cost = if (promptTokens > 0 || completionTokens > 0)
            "  ·  $promptTokens in / $completionTokens out" else ""
        b.appendLine("▸ ANSWER  #$turn  (finish=${finishReason ?: "unknown"}$cost)")
        b.appendLine(indent(ReasoningModule.redact(text.ifBlank { "(no text — tool calls only)" })))
        b.appendLine(endRule())
        return b.toString()
    }

    // ---- live: the background triage loop -------------------------------------------

    /**
     * One notification and what the agent decided about it. This is the half an audience
     * finds surprising — it happens with nobody touching the phone.
     */
    fun triage(notif: CapturedNotif, decision: ActionDecision) {
        if (!enabled) return
        emit(StringBuilder(formatTriage(notif.appName, notif.title, notif.text, decision)))
    }

    // ---- replay ---------------------------------------------------------------------

    /**
     * Reprints what is on disk: the active conversation and the recent triage decisions.
     *
     * Deliberately not gated on [enabled] — see the class note.
     *
     * **What replay cannot show**, and says so rather than implying completeness: prompts,
     * tool calls and tool results are not persisted anywhere. Only settled chat turns and
     * `ActionDecision` rows survive a restart, so a replay is the narrative, and live
     * tracing is the only way to see what the model was actually looking at.
     */
    fun dump() = emit(StringBuilder(formatDump()))

    /** Pure half of [dump]. */
    internal fun formatDump(): String {
        val b = StringBuilder()
        b.appendLine(rule("REPLAY", "from persisted history"))

        val decisions = DecisionStore.decisions.value
        b.appendLine("▸ TRIAGE DECISIONS  (${decisions.size} in memory)")
        if (decisions.isEmpty()) {
            b.appendLine(indent("(none yet)"))
        } else {
            decisions.takeLast(REPLAY_DECISIONS).forEach { d ->
                b.appendLine(indent(oneLineDecision(d)))
            }
        }

        val messages = ChatStore.messages.value.filter { !it.pending }
        b.appendLine("▸ CONVERSATION  (${messages.size} settled turn(s))")
        if (messages.isEmpty()) {
            b.appendLine(indent("(none yet)"))
        } else {
            messages.takeLast(REPLAY_TURNS).forEach { m ->
                val who = when (m.role) {
                    ChatMessage.Role.USER -> "USER     "
                    ChatMessage.Role.ASSISTANT -> "ASSISTANT"
                    ChatMessage.Role.SYSTEM -> "AGENT    "
                }
                b.appendLine(indent("$who  ${ReasoningModule.redact(m.content)}"))
            }
        }

        b.appendLine(indent("prompts, tool calls and tool results are not persisted —"))
        b.appendLine(indent("enable the reasoning trace in Settings to see those live."))
        b.appendLine(endRule())
        return b.toString()
    }

    // ---- formatting -----------------------------------------------------------------

    internal fun formatTriage(
        appName: String, title: String, text: String, decision: ActionDecision
    ): String {
        val b = StringBuilder()
        b.appendLine(rule("TRIAGE", appName))
        val head = ReasoningModule.redact(title).ifBlank { "(no title)" }
        b.appendLine("▸ NOTIFICATION  $head")
        if (text.isNotBlank()) b.appendLine(indent(ReasoningModule.redact(text)))
        b.appendLine("▸ DECISION  ${oneLineDecision(decision)}")
        if (decision.reasoning.isNotBlank()) {
            b.appendLine(indent(ReasoningModule.redact(decision.reasoning)))
        }
        // The two fields that only matter for one action each, shown only then.
        if (decision.replyText.isNotBlank()) {
            b.appendLine(indent("reply: \"${ReasoningModule.redact(decision.replyText)}\""))
        }
        if (decision.actionTitle.isNotBlank()) {
            b.appendLine(indent("button: \"${decision.actionTitle}\""))
        }
        b.appendLine(endRule())
        return b.toString()
    }

    /** `escalate · high · confidence 0.94` — the shape used in both live and replay. */
    private fun oneLineDecision(d: ActionDecision): String = buildString {
        append(d.action.name.lowercase(Locale.US))
        append(" · ").append(d.priority.name.lowercase(Locale.US))
        append(" · confidence ").append(String.format(Locale.US, "%.2f", d.confidence))
        d.snoozeDurationMinutes?.let { append(" · snooze ").append(it).append("m") }
    }

    private fun rule(kind: String, subject: String): String {
        val head = "════ $kind ─ ${clock.format(Date())} ─ $subject "
        return head + "═".repeat((78 - head.length).coerceAtLeast(4))
    }

    private fun endRule(): String = "═".repeat(78)

    private fun indent(text: String): String =
        text.trimEnd().lines().joinToString("\n") { "    $it" }

    /**
     * One log entry per line, hard-wrapping anything past [MAX_LINE]. Logcat merges the
     * lines back into one readable block on the workstation, and the per-entry limit
     * never truncates a prompt mid-sentence.
     */
    private fun emit(block: StringBuilder) {
        block.toString().trimEnd().lines().forEach { line ->
            if (line.length <= MAX_LINE) {
                Log.i(TAG, line)
            } else {
                var i = 0
                while (i < line.length) {
                    val end = minOf(i + MAX_LINE, line.length)
                    Log.i(TAG, (if (i == 0) "" else "    ↳ ") + line.substring(i, end))
                    i = end
                }
            }
        }
    }

    /** Enough to tell the story of a demo without flooding the buffer. */
    private const val REPLAY_DECISIONS = 15
    private const val REPLAY_TURNS = 20

    /** Convenience for [AgentSpec]-less callers: the JSON tool catalog's size. */
    fun toolCount(toolsJson: JSONArray?): Int = toolsJson?.length() ?: 0
}
