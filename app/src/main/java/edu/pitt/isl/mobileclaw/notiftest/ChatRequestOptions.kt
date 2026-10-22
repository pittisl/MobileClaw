package edu.pitt.isl.mobileclaw.notiftest

import org.json.JSONObject
import java.net.URI

/** Provider-specific request options, without changing prompts or callable schemas. */
object ChatRequestOptions {
    /** Adds at most one option to the newly built request; its model is the source of truth. */
    fun applyTo(endpoint: String, body: JSONObject): JSONObject {
        if (body.optString("model") != "gemini-2.5-flash" ||
            (body.optJSONArray("tools")?.length() ?: 0) == 0 || body.has("reasoning_effort")) return body
        val uri = try { URI(endpoint) } catch (_: Exception) { return body }
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            !uri.host.equals("generativelanguage.googleapis.com", ignoreCase = true)) return body

        // On the complete 24-tool catalog, repeated device probes returned empty
        // STOP turns with the provider's default thinking budget. A fixed nonzero
        // budget helped the original prompt but failed again after a prompt change;
        // disabling internal thinking restored calls without changing tools or prose.
        // This is an observed compatibility setting, not a claim about Google's
        // internal cause. The documented Gemini 2.5 option disables the internal
        // thinking budget: https://ai.google.dev/gemini-api/docs/openai#thinking
        // Leave an explicitly supplied reasoning_effort alone if one is added later.
        body.put("reasoning_effort", "none")
        return body
    }
}
