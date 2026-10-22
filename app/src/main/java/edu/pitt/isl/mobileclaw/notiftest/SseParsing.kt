package edu.pitt.isl.mobileclaw.notiftest

import org.json.JSONArray
import org.json.JSONObject
import java.util.TreeMap

/**
 * Network-free accumulator for one streamed assistant turn. Fed one SSE `data:`
 * payload at a time via [onPayload], it reassembles token fragments and tool-call
 * fragments (which arrive split across chunks, keyed by index) and tracks the
 * finish reason. Kept separate from [OpenAiAiClient] so this fiddly reassembly can
 * be unit-tested against captured transcripts without hitting the network.
 */
internal class SseTurn {

    private class ToolAcc {
        var id: String = ""
        var name: String = ""
        val args = StringBuilder()
    }

    private val toolBuilders = TreeMap<Int, ToolAcc>()

    /** The reassembled assistant text so far. */
    val content = StringBuilder()

    var finishReason: String? = null
        private set

    /** Tokens billed for this request, once the provider reports them. 0 until then. */
    var promptTokens = 0
        private set
    var completionTokens = 0
        private set

    /**
     * Feeds one SSE payload's JSON (the part after `data: `). Returns the content
     * fragment in this chunk (empty when the chunk carried only a tool-call fragment,
     * a role marker, or finish metadata) so the caller can emit a token delta.
     */
    fun onPayload(payload: String): String {
        val json = try { JSONObject(payload) } catch (_: Exception) { return "" }
        // Usage comes last and, with `stream_options.include_usage`, in a chunk whose
        // `choices` array is *empty* — so it has to be read before the early return
        // below, not after it.
        readUsage(json)
        val choices = json.optJSONArray("choices") ?: return ""
        if (choices.length() == 0) return ""
        val choice0 = choices.getJSONObject(0)
        var frag = ""
        choice0.optJSONObject("delta")?.let { delta ->
            if (delta.has("content") && !delta.isNull("content")) frag = delta.getString("content")
            delta.optJSONArray("tool_calls")?.let { tcs ->
                for (i in 0 until tcs.length()) accumulate(tcs.getJSONObject(i))
            }
        }
        if (!choice0.isNull("finish_reason")) finishReason = choice0.optString("finish_reason")
        if (frag.isNotEmpty()) content.append(frag)
        return frag
    }

    /**
     * Assigns rather than accumulates: providers report running totals for the whole
     * request, so adding successive chunks up would multiply the bill.
     */
    private fun readUsage(json: JSONObject) {
        val u = json.optJSONObject("usage") ?: return
        promptTokens = u.optInt("prompt_tokens", promptTokens)
        completionTokens = u.optInt("completion_tokens", completionTokens)
    }

    /**
     * Resolves a provider's tool-call index to a local slot. Some Gemini-compatible
     * streams put parallel calls at index 0 while giving each call a distinct id.
     * A new id at an occupied index therefore opens a new slot. Subsequent fragments
     * without an id continue the most recently opened slot at that index, avoiding
     * concatenation of arguments from separate calls.
     */
    private val slotFor = HashMap<Int, Int>()

    private fun accumulate(tc: JSONObject) {
        val idx = tc.optInt("index", 0)
        val id = if (tc.has("id") && !tc.isNull("id")) tc.getString("id") else null
        val current = slotFor[idx] ?: idx
        val key = if (id != null && toolBuilders[current]?.let { it.id.isNotBlank() && it.id != id } == true) {
            (toolBuilders.lastKey() + 1).also { slotFor[idx] = it }
        } else current
        val b = toolBuilders.getOrPut(key) { ToolAcc() }
        if (id != null) b.id = id
        tc.optJSONObject("function")?.let { fn ->
            if (fn.has("name") && !fn.isNull("name")) b.name = fn.getString("name")
            if (fn.has("arguments") && !fn.isNull("arguments")) {
                val frag = fn.getString("arguments")
                // Gemini's OpenAI-compatible stream opens some calls with a literal "{}"
                // fragment and then sends the real object; concatenated that is "{}{…}",
                // which org.json reads as the empty object and every required argument
                // goes missing. The
                // placeholder is only ever the first fragment, so it is replaced, not kept.
                if (frag.isNotEmpty() && b.args.toString() == "{}") b.args.setLength(0)
                b.args.append(frag)
            }
        }
    }

    fun hasToolCalls(): Boolean = toolBuilders.isNotEmpty()

    /** Reassembled tool calls in index order: (id, name, argumentsJson). */
    fun toolCalls(): List<Triple<String, String, String>> =
        toolBuilders.entries.map { (idx, b) ->
            Triple(b.id.ifBlank { "call_$idx" }, b.name, b.args.toString().ifBlank { "{}" })
        }

    /** The assistant turn (content + tool_calls) to echo back into the next request. */
    fun assistantTurnJson(): JSONObject {
        val tcArr = JSONArray()
        for ((idx, b) in toolBuilders) {
            tcArr.put(
                JSONObject()
                    .put("id", b.id.ifBlank { "call_$idx" })
                    .put("type", "function")
                    .put("function", JSONObject().put("name", b.name).put("arguments", b.args.toString().ifBlank { "{}" }))
            )
        }
        return JSONObject()
            .put("role", "assistant")
            .put("content", content.toString().ifBlank { JSONObject.NULL })
            .put("tool_calls", tcArr)
    }

    /**
     * Fallback for a provider that ignored `stream:true` and returned a single
     * completion body. Fills tool calls (keyed by position, since non-streamed calls
     * carry no index) and appends + returns the message text.
     */
    fun fallbackFromWholeBody(body: String): String {
        return try {
            val whole = JSONObject(body)
            readUsage(whole)
            val choice = whole.getJSONArray("choices").getJSONObject(0)
            finishReason = choice.optString("finish_reason", "").takeIf { it.isNotBlank() && it != "null" }
            val msg = choice.getJSONObject("message")
            msg.optJSONArray("tool_calls")?.let { tcs ->
                for (i in 0 until tcs.length()) {
                    val tc = tcs.getJSONObject(i)
                    val b = toolBuilders.getOrPut(i) { ToolAcc() }
                    b.id = tc.optString("id")
                    tc.optJSONObject("function")?.let { fn ->
                        b.name = fn.optString("name")
                        b.args.append(fn.optString("arguments", ""))
                    }
                }
            }
            val text = msg.optString("content", "")
            if (text.isNotEmpty()) content.append(text)
            text
        } catch (_: Exception) {
            ""
        }
    }
}

/**
 * Pure helpers for the streamed-markdown + [ACTIONS_SENTINEL] contract, shared by
 * the client's final-message parse and the UI's live display. Kept free of Android
 * types so they're unit-testable.
 */
internal object StreamText {

    /** Splits fully-streamed text into (visible markdown, quick-reply action labels). */
    fun split(raw: String): Pair<String, List<String>> {
        val trimmed = raw.trim()
        val idx = trimmed.indexOf(ACTIONS_SENTINEL)
        if (idx < 0) return trimmed to emptyList()
        val text = trimmed.substring(0, idx).trimEnd()
        val actions = trimmed.substring(idx + ACTIONS_SENTINEL.length)
            .split("|").map { it.trim() }.filter { it.isNotEmpty() }.take(4)
        return (text.ifEmpty { trimmed }) to actions
    }

    /**
     * The text to show mid-stream: everything before the sentinel, with a
     * partially-arrived sentinel hidden so "@@AC" doesn't flash before it completes.
     */
    fun visible(raw: String): String {
        val full = raw.substringBefore(ACTIONS_SENTINEL)
        if (full.length < raw.length) return full.trimEnd() // sentinel fully present
        for (len in (ACTIONS_SENTINEL.length - 1) downTo 1) {
            if (full.length >= len && full.endsWith(ACTIONS_SENTINEL.substring(0, len))) {
                return full.substring(0, full.length - len)
            }
        }
        return full
    }
}
