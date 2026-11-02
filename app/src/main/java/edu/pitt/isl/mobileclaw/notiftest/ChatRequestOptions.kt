package edu.pitt.isl.mobileclaw.notiftest

import org.json.JSONObject
import java.net.URI

/** Provider-specific request options, without changing prompts or callable schemas. */
object ChatRequestOptions {
    /** Apply model-specific options to every request path, including JSON-only calls. */
    fun applyTo(endpoint: String, body: JSONObject): JSONObject {
        val uri = try { URI(endpoint) } catch (_: Exception) { return body }
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            !uri.host.equals("generativelanguage.googleapis.com", ignoreCase = true)) return body
        val hasTools = (body.optJSONArray("tools")?.length() ?: 0) > 0
        when (body.optString("model")) {
            "gemini-3.5-flash-lite" -> {
                // 3.5 ignores custom sampling; use the provider's supported defaults.
                listOf("temperature", "top_p", "top_k").forEach { body.remove(it) }
                val thinking = body.optJSONObject("extra_body")?.optJSONObject("google")
                    ?.optJSONObject("thinking_config")
                if (!body.has("reasoning_effort") && thinking == null && hasTools) {
                    body.put("reasoning_effort", "medium")
                }
            }
            "gemini-2.5-flash" -> {
                // Compatibility for explicitly selected legacy models before retirement.
                if (hasTools && !body.has("reasoning_effort")) body.put("reasoning_effort", "none")
            }
        }
        return body
    }
}
