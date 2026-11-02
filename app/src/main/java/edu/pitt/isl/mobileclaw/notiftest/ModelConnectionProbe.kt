package edu.pitt.isl.mobileclaw.notiftest

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume

/**
 * Explicit, synthetic checks of the endpoint the user entered. No device context,
 * real tool, stored credential fallback, or alternate endpoint is consulted.
 * A supported result describes this small probe, not arbitrary model reliability.
 *
 * Parsing runs on OkHttp's callback thread. The cancellation handler remains installed
 * while the response body is read, so cancelling the screen also closes a stalled SSE
 * connection. All user-visible details below are fixed strings; provider bodies and
 * exception messages must never become UI text.
 */
class ModelConnectionProbe internal constructor(
    private val http: OkHttpClient = OkHttpClient(),
) {
    enum class Status { SUPPORTED, UNSUPPORTED, FAILED, NOT_CHECKED }

    data class Check(val status: Status, val detail: String)

    data class Result(
        val streaming: Check,
        val toolCalling: Check,
        val structuredOutput: Check,
        val vision: Check,
    )

    suspend fun test(profile: LlmProfile): Result {
        val normalized = try {
            profile.validated().also { require(it.isConfigured) }
        } catch (_: IllegalArgumentException) {
            val invalid = Check(Status.FAILED, "Invalid model settings or missing API key.")
            return Result(invalid, invalid, invalid,
                Check(Status.NOT_CHECKED, "Vision was not tested because model settings are invalid."))
        }
        val client = http.newBuilder()
            // Never forward a credential to a redirect target, including a downgrade.
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(minOf(10L, normalized.readTimeoutSeconds), TimeUnit.SECONDS)
            .readTimeout(normalized.readTimeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(normalized.readTimeoutSeconds, TimeUnit.SECONDS)
            .build()
        val streaming = check(client, normalized, Mode.STREAMING)
        val tools = check(client, normalized, Mode.TOOLS)
        val structured = check(client, normalized, Mode.STRUCTURED)
        val vision = if (normalized.supportsImages) check(client, normalized, Mode.VISION)
            else notCheckedVision()
        return Result(streaming, tools, structured, vision)
    }

    private enum class Mode { STREAMING, TOOLS, TOOL_RESULT, STRUCTURED, VISION }
    private data class Attempt(
        val check: Check,
        val rejectedUsageOption: Boolean = false,
        val assistantTurn: JSONObject? = null,
        val usageOmitted: Boolean = false,
    )

    private suspend fun check(client: OkHttpClient, profile: LlmProfile, mode: Mode): Check {
        suspend fun runAttempt(stage: Mode, assistantTurn: JSONObject? = null, allowUsage: Boolean = true): Attempt {
            val streamed = stage == Mode.STREAMING || stage == Mode.TOOLS || stage == Mode.TOOL_RESULT
            val first = attempt(client, profile, stage, includeUsage = streamed && allowUsage, assistantTurn = assistantTurn)
            if (!first.rejectedUsageOption) return first
            // Retry only the rejected request, including a rejected tool-result request.
            // The accepted function-call response and its signature stay unchanged.
            val second = attempt(client, profile, stage, includeUsage = false, assistantTurn = assistantTurn)
            return if (second.check.status == Status.SUPPORTED) {
                second.copy(check = second.check.copy(detail = second.check.detail + " Usage reporting option omitted."), usageOmitted = true)
            } else second
        }
        val first = runAttempt(mode)
        if (mode != Mode.TOOLS || first.check.status != Status.SUPPORTED) return first.check
        val final = runAttempt(Mode.TOOL_RESULT, requireNotNull(first.assistantTurn), allowUsage = !first.usageOmitted).check
        return if (first.usageOmitted && final.status == Status.SUPPORTED)
            final.copy(detail = final.detail + " Usage reporting option omitted.") else final
    }

    private suspend fun attempt(
        client: OkHttpClient,
        profile: LlmProfile,
        mode: Mode,
        includeUsage: Boolean,
        assistantTurn: JSONObject? = null,
    ): Attempt = suspendCancellableCoroutine { continuation ->
        val request = try {
            Request.Builder()
                .url(profile.endpoint)
                .header("Accept", if (mode == Mode.STREAMING || mode == Mode.TOOLS || mode == Mode.TOOL_RESULT)
                    "text/event-stream" else "application/json")
                .apply {
                    if (profile.auth == LlmAuth.BEARER) header("Authorization", "Bearer ${profile.apiKey}")
                }
                .post(ChatRequestOptions.applyTo(profile.endpoint, requestBody(profile.model, mode, includeUsage, assistantTurn)).toString().toRequestBody(JSON_MEDIA))
                .build()
        } catch (_: IllegalArgumentException) {
            // OkHttp's header validation errors can contain the submitted header value.
            continuation.resume(Attempt(Check(Status.FAILED, "Invalid model connection settings.")))
            return@suspendCancellableCoroutine
        }
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(Attempt(networkFailure(e)))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use {
                        if (!it.isSuccessful) {
                            val hint = includeUsage && it.code == 400 && rejectsUsageOption(readBody(it))
                            Attempt(httpFailure(it.code), rejectedUsageOption = hint)
                        } else if (mode == Mode.TOOLS) {
                            val turn = readStream(it)
                            val check = validateTools(turn)
                            Attempt(check, assistantTurn = if (check.status == Status.SUPPORTED) turn.assistantTurnJson() else null)
                        } else Attempt(validate(it, mode))
                    }
                } catch (e: IOException) {
                    Attempt(networkFailure(e))
                } catch (_: Exception) {
                    Attempt(Check(Status.UNSUPPORTED, "The response did not match the probe format."))
                }
                if (continuation.isActive) continuation.resume(result)
            }
        })
    }

    private fun requestBody(model: String, mode: Mode, includeUsage: Boolean, assistantTurn: JSONObject?): JSONObject {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content",
            "This is a synthetic MobileClaw connection test. Follow the requested output format exactly."))
        val content: Any = when (mode) {
            Mode.STREAMING -> "Reply with exactly $MARKER and no other text."
            Mode.TOOLS, Mode.TOOL_RESULT -> "Call $TOOL once with marker set to $MARKER. " +
                "After receiving its result, reply with exactly $MARKER and no other text."
            Mode.STRUCTURED -> "Classify this synthetic system status: a test is idle and requires no action. " +
                "Return only a JSON object with action=do_nothing, priority=low, a nonempty reasoning string, " +
                "reply_text and action_title as empty strings, confidence as a number from 0 to 1, " +
                "and snooze_duration_minutes as null. Include exactly those seven fields."
            Mode.VISION -> JSONArray()
                .put(JSONObject().put("type", "text").put("text",
                    "What is the dominant color of this square? Reply with exactly one lowercase color word."))
                .put(JSONObject().put("type", "image_url").put("image_url",
                    JSONObject().put("url", "data:image/png;base64,$SYNTHETIC_IMAGE")))
        }
        messages.put(JSONObject().put("role", "user").put("content", content))
        if (mode == Mode.TOOL_RESULT) {
            val previous = requireNotNull(assistantTurn)
            val call = previous.getJSONArray("tool_calls").getJSONObject(0)
            messages.put(previous)
            messages.put(JSONObject().put("role", "tool")
                .put("tool_call_id", call.getString("id"))
                .put("name", call.getJSONObject("function").getString("name"))
                .put("content", JSONObject().put("marker", MARKER).toString()))
        }
        return JSONObject().put("model", model).put("messages", messages)
            .put("max_tokens", if (model == "gemini-3.5-flash-lite") 4096 else 192)
            .put("stream", mode == Mode.STREAMING || mode == Mode.TOOLS || mode == Mode.TOOL_RESULT)
            .apply {
                if (includeUsage) put("stream_options", JSONObject().put("include_usage", true))
                if (mode == Mode.STRUCTURED) put("response_format", JSONObject().put("type", "json_object"))
                if (mode == Mode.TOOL_RESULT) put("tool_choice", "none")
                if (mode == Mode.TOOLS || mode == Mode.TOOL_RESULT) put("tools", JSONArray().put(JSONObject()
                    .put("type", "function").put("function", JSONObject()
                        .put("name", TOOL)
                        .put("description", "Synthetic diagnostic only; this function is never executed.")
                        .put("parameters", JSONObject().put("type", "object")
                            .put("properties", JSONObject().put("marker", JSONObject()
                                .put("type", "string").put("enum", JSONArray().put(MARKER))))
                            .put("required", JSONArray().put("marker"))
                            .put("additionalProperties", false)))))
            }
    }

    private fun validate(response: Response, mode: Mode): Check = when (mode) {
        Mode.STREAMING -> {
            val stream = readStream(response)
            if (stream.content.toString().trim() == MARKER && stream.finishReason == "stop" && !stream.hasToolCalls())
                Check(Status.SUPPORTED, "Received the expected text through a completed SSE stream.")
            else Check(Status.UNSUPPORTED, "The SSE text probe did not complete with the expected response.")
        }
        Mode.TOOLS -> error("Tool-call responses are parsed with their replay metadata.")
        Mode.TOOL_RESULT -> {
            val stream = readStream(response)
            if (stream.content.toString().trim() == MARKER && stream.finishReason == "stop" && !stream.hasToolCalls())
                Check(Status.SUPPORTED, "Completed the streamed tool-call round trip with a synthetic result; no tool was executed.")
            else Check(Status.UNSUPPORTED, "The model did not complete the synthetic tool-result round trip.")
        }
        Mode.STRUCTURED -> {
            val obj = strictObject(completionText(response))
            if (validTriage(obj)) Check(Status.SUPPORTED, "Received valid triage JSON with all expected fields.")
            else Check(Status.UNSUPPORTED, "The response did not contain the expected triage JSON fields.")
        }
        Mode.VISION -> {
            if (completionText(response).trim().lowercase() == "red")
                Check(Status.SUPPORTED, "Recognized the synthetic image's dominant color.")
            else Check(Status.UNSUPPORTED, "The synthetic image response did not match the expected color.")
        }
    }

    private fun validateTools(stream: SseTurn): Check {
        val calls = stream.toolCalls()
        val args = calls.singleOrNull()?.let { strictObject(it.third) }
        // Gemini 3.5 completes tool-call streams with "stop"; the call payload
        // identifies a tool response. Still reject truncated or filtered streams.
        return if (stream.finishReason in setOf("tool_calls", "stop") && calls.size == 1 && calls[0].second == TOOL &&
            args != null && args.length() == 1 && args.opt("marker") == MARKER)
            Check(Status.SUPPORTED, "Reassembled the expected streamed tool call.")
        else Check(Status.UNSUPPORTED, "The streamed tool call or its arguments did not match the probe.")
    }

    private fun validTriage(obj: JSONObject): Boolean {
        val keys = setOf("action", "priority", "reasoning", "reply_text", "action_title", "confidence", "snooze_duration_minutes")
        val confidence = obj.opt("confidence") as? Number ?: return false
        return obj.keys().asSequence().toSet() == keys && obj.opt("action") == "do_nothing" && obj.opt("priority") == "low" &&
            (obj.opt("reasoning") as? String)?.isNotBlank() == true &&
            obj.opt("reply_text") == "" && obj.opt("action_title") == "" &&
            confidence.toDouble().isFinite() && confidence.toDouble() in 0.0..1.0 &&
            obj.isNull("snooze_duration_minutes")
    }

    private fun completionText(response: Response): String =
        strictObject(readBody(response)).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").get("content") as String

    /** Bounded SSE framing; SseTurn is the same accumulator used by production chat. */
    private fun readStream(response: Response): SseTurn {
        val source = requireNotNull(response.body).source()
        val turn = SseTurn()
        val event = StringBuilder()
        var bytes = 0L
        var sawPayload = false
        var done = false
        fun consume() {
            if (event.isEmpty()) return
            val payload = event.toString().trimEnd('\n')
            event.setLength(0)
            if (payload == "[DONE]") { done = true; return }
            val json = strictObject(payload)
            require(!json.has("error"))
            turn.onPayload(payload)
            sawPayload = true
        }
        while (!done && !source.exhausted()) {
            val line = source.readUtf8LineStrict(MAX_RESPONSE_BYTES)
            bytes += line.toByteArray(Charsets.UTF_8).size + 1
            require(bytes <= MAX_RESPONSE_BYTES)
            if (line.isEmpty()) consume()
            else if (line.startsWith("data:")) {
                event.append(line.substring(5).removePrefix(" ")).append('\n')
            }
        }
        if (!done) consume()
        require(sawPayload)
        return turn
    }

    private fun readBody(response: Response): String {
        val source = requireNotNull(response.body).source()
        source.request(MAX_RESPONSE_BYTES + 1)
        require(source.buffer.size <= MAX_RESPONSE_BYTES)
        return source.readUtf8()
    }

    /** JSONObject alone accepts a valid prefix followed by junk; reject that case. */
    private fun strictObject(text: String): JSONObject {
        val tokener = JSONTokener(text)
        val value = tokener.nextValue()
        require(value is JSONObject && tokener.nextClean() == '\u0000')
        return value
    }

    private fun rejectsUsageOption(body: String): Boolean = try {
        val error = strictObject(body).optJSONObject("error")
        val param = error?.optString("param", "").orEmpty()
        val message = error?.optString("message", "").orEmpty().lowercase()
        val code = error?.optString("code", "").orEmpty()
        val rejectsField = code == "unsupported_parameter" || listOf(
            "unsupported", "not supported", "unrecognized", "unknown", "unexpected", "not allowed"
        ).any(message::contains)
        val namedParam = param == "stream_options" || param == "stream_options.include_usage"
        val option = "(?:stream_options(?:\\.include_usage)?|include_usage)"
        val directRejection = Regex(
            "(?:unsupported|unrecognized|unknown|unexpected)\\s+" +
                "(?:(?:request|body|argument|parameter|field|option|supplied|named)\\s*[:=]?\\s*){0,4}" +
                "[\"']?$option\\b"
        ).containsMatchIn(message) || Regex(
            "\\b$option[\"']?\\s+(?:is\\s+|are\\s+)?(?:not supported|unsupported|not allowed|unrecognized|unknown|unexpected)"
        ).containsMatchIn(message)
        (namedParam && rejectsField) || directRejection ||
            (code == "unsupported_parameter" && Regex("\\b$option\\b").containsMatchIn(message))
    } catch (_: Exception) { false }

    private fun httpFailure(code: Int): Check = when (code) {
        in 300..399 -> Check(Status.FAILED, "Redirect refused. Enter the final endpoint URL directly.")
        401, 403 -> Check(Status.FAILED, "Authentication or endpoint permission was rejected.")
        400, 422 -> Check(Status.UNSUPPORTED, "The endpoint rejected this capability probe (HTTP $code).")
        404 -> Check(Status.FAILED, "The endpoint or requested model was not found.")
        429 -> Check(Status.FAILED, "The endpoint is rate-limited or has no available quota.")
        else -> Check(Status.FAILED, "The endpoint returned HTTP $code.")
    }

    private fun networkFailure(error: IOException): Check = Check(Status.FAILED, when (error) {
        is InterruptedIOException -> "The connection test timed out."
        is UnknownHostException -> "The model host could not be resolved."
        is SSLException -> "The TLS connection could not be established."
        else -> "The model connection failed or ended before the response completed."
    })

    private fun notCheckedVision() = Check(Status.NOT_CHECKED, "Image input is disabled; vision was not tested.")

    private companion object {
        const val MARKER = "MOBILECLAW_PROBE_OK"
        const val TOOL = "mobileclaw_probe"
        const val MAX_RESPONSE_BYTES = 65_536L
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        // A 32 × 32 solid-red RGB PNG, generated from fixed bytes; no Android APIs or user files.
        const val SYNTHETIC_IMAGE = "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAIAAAD8GO2jAAAAKElEQVR4nO3NsQ0AAAzCMP5/un0CNkuZ41wybXsHAAAAAAAAAAAAxR4yw/wuPL6QkAAAAABJRU5ErkJggg=="
    }
}
