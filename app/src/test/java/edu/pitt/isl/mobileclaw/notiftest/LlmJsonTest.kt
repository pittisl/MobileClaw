package edu.pitt.isl.mobileclaw.notiftest

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class LlmJsonTest {
    private val obj = """{"action":"reply","confidence":0.9,"reasoning":"a {nested} brace"}"""

    @Test
    fun bareObjectIsUnchanged() = assertEquals(obj, LlmJson.extractObject(obj))

    @Test
    fun markdownFenceIsStripped() {
        val fenced = "```json\n$obj\n```"
        assertEquals("reply", JSONObject(LlmJson.extractObject(fenced)).getString("action"))
    }

    @Test
    fun leadingProseIsDropped() {
        val chatty = "Here is the decision:\n$obj\nLet me know if you need more."
        assertEquals(obj, LlmJson.extractObject(chatty))
    }

    @Test
    fun textWithoutAnObjectIsReturnedAsIsSoTheParseErrorNamesIt() {
        val prompt = "=== INCOMING NOTIFICATION ANALYSIS ===\nApp: x"
        assertEquals(prompt, LlmJson.extractObject(prompt))
    }
}
