package edu.pitt.isl.mobileclaw.notiftest

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatRequestOptionsTest {
    // Android's compile-time JSONObject API lacks upstream org.json's similar().
    private fun JSONObject.similar(other: JSONObject): Boolean = canonical(this) == canonical(other)

    private fun canonical(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().associateWith { canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { canonical(value.get(it)) }
        else -> value
    }

    @Test fun googleFlashWithToolsAddsOnlyTheDocumentedThinkingOption() {
        val body = request()
        val before = JSONObject(body.toString())
        val originalTools = body.getJSONArray("tools")
        val originalMessages = body.getJSONArray("messages")
        assertSame(body, ChatRequestOptions.applyTo(GOOGLE, body))
        assertEquals("none", body.getString("reasoning_effort"))
        assertSame(originalTools, body.getJSONArray("tools"))
        assertSame(originalMessages, body.getJSONArray("messages"))
        val withoutOption = JSONObject(body.toString()).apply { remove("reasoning_effort") }
        assertTrue("All pre-existing request data, including parameter prose, is unchanged", before.similar(withoutOption))
    }

    @Test fun providerBoundaryUsesParsedHttpsHostRatherThanStringMatching() {
        for (endpoint in listOf(
            "https://api.openai.com/v1/chat/completions",
            "http://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            "https://generativelanguage.googleapis.com.example.org/v1/chat/completions",
            "https://proxy.generativelanguage.googleapis.com/v1/chat/completions",
            "https://generativelanguage.googleapis.com@example.org/v1/chat/completions",
            "http://127.0.0.1:8080/v1/chat/completions",
            "https://localhost/v1/chat/completions?upstream=generativelanguage.googleapis.com",
            "https://example.org/generativelanguage.googleapis.com/v1/chat/completions",
        )) {
            val body = request()
            val before = JSONObject(body.toString())
            ChatRequestOptions.applyTo(endpoint, body)
            assertTrue(endpoint, before.similar(body))
        }
    }

    @Test fun otherModelsIncludingProAndFlashLiteAreUntouched() {
        for (model in listOf("gemini-2.5-pro", "gemini-2.5-flash-lite", "gemini-3-flash",
            "gemini-2.5-flash-preview", "models/gemini-2.5-flash", "gpt-4o", "")) {
            val body = request().put("model", model)
            val before = JSONObject(body.toString())
            ChatRequestOptions.applyTo(GOOGLE, body)
            assertTrue(model, before.similar(body))
        }
    }

    @Test fun missingEmptyOrMalformedToolsDoNotChangeNonToolRequests() {
        val bodies = listOf(
            request().apply { remove("tools") },
            request().put("tools", JSONArray()),
            request().put("tools", JSONObject.NULL),
            request().put("tools", JSONObject()),
        )
        for (body in bodies) {
            val before = JSONObject(body.toString())
            ChatRequestOptions.applyTo(GOOGLE, body)
            assertTrue(before.similar(body))
        }
    }

    @Test fun missingModelNeverFallsBackToSomeConfiguredOrDefaultModel() {
        val body = request().apply { remove("model") }
        val before = JSONObject(body.toString())
        ChatRequestOptions.applyTo(GOOGLE, body)
        assertTrue(before.similar(body))
    }

    @Test fun malformedAndRelativeEndpointsStayUnchanged() {
        for (endpoint in listOf("not a url", "/v1/chat/completions", "//generativelanguage.googleapis.com/path",
            "https://generativelanguage.googleapis.com/%invalid", "")) {
            val body = request()
            val before = JSONObject(body.toString())
            ChatRequestOptions.applyTo(endpoint, body)
            assertTrue(endpoint, before.similar(body))
        }
    }

    @Test fun hostnameAndSchemeUseTheirCaseInsensitiveUrlSemantics() {
        val body = request()
        ChatRequestOptions.applyTo("HTTPS://GENERATIVELANGUAGE.GOOGLEAPIS.COM/v1beta/openai/chat/completions", body)
        assertEquals("none", body.getString("reasoning_effort"))
    }

    @Test fun explicitChoiceAndRepeatedApplicationArePreserved() {
        val explicit = request().put("reasoning_effort", "high")
        val before = JSONObject(explicit.toString())
        ChatRequestOptions.applyTo(GOOGLE, explicit)
        assertTrue(before.similar(explicit))
        val body = ChatRequestOptions.applyTo(GOOGLE, request())
        val once = JSONObject(body.toString())
        ChatRequestOptions.applyTo(GOOGLE, body)
        assertTrue(once.similar(body))
    }

    private fun request(): JSONObject = JSONObject("""{
        "model":"gemini-2.5-flash","messages":[{"role":"user","content":"Find yesterday's dog photo."}],
        "temperature":0.2,"stream":true,"stream_options":{"include_usage":true},
        "tools":[{"type":"function","function":{"name":"search_files","description":"Keep the complete function description.",
          "parameters":{"type":"object","properties":{"query":{"type":"string","description":"Keep the parameter description."}},
            "required":["query"],"additionalProperties":false}}}],
        "tool_choice":{"type":"function","function":{"name":"search_files"}}
    }""")

    companion object {
        private const val GOOGLE = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
    }
}
