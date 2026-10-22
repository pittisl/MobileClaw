package edu.pitt.isl.mobileclaw.notiftest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolProgressTest {
    @Test
    fun completionIsMatchedByCallId_andNeverDisplaysPrivateArgumentsOrResults() {
        val progress = ToolProgress()
        progress.update(AgentEvent.ToolCall("contact", "lookup_contact", "PRIVATE CONTACT QUERY"))
        progress.update(AgentEvent.ToolCall("calendar", "read_calendar", "PRIVATE CALENDAR QUERY"))
        progress.update(AgentEvent.ToolResult("calendar", "PRIVATE CALENDAR EVENT"))

        assertEquals("Looking up contact…\nCalendar lookup finished", progress.text)
        assertFalse(progress.update(AgentEvent.ToolResult("unknown", "PRIVATE RESULT")))
        assertFalse(progress.text.contains("PRIVATE"))

        // A completed call does not promise success; tool errors are results too.
        progress.update(AgentEvent.ToolResult("contact", "Error: permission denied"))
        assertEquals("Contact lookup finished\nCalendar lookup finished", progress.text)
    }

    @Test
    fun onlyTheThreeMostRecentKnownReadsAreShown() {
        val progress = ToolProgress()
        progress.update(AgentEvent.ToolCall("one", "lookup_contact", "{}"))
        progress.update(AgentEvent.ToolCall("two", "read_calendar", "{}"))
        progress.update(AgentEvent.ToolCall("three", "search_files", "{}"))
        assertFalse(progress.update(AgentEvent.ToolCall("write", "send_sms", "PRIVATE MESSAGE")))
        progress.update(AgentEvent.ToolCall("four", "recall", "{}"))

        assertEquals("Checking calendar…\nSearching files…\nChecking saved memory…", progress.text)
        assertFalse(progress.update(AgentEvent.ToolResult("one", "old result")))
        assertTrue(progress.update(AgentEvent.ToolResult("three", "file result")))
        assertTrue(progress.text.contains("File search finished"))
    }
}
