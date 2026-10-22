package edu.pitt.isl.mobileclaw.notiftest

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Quick-reply labels are JSON-encoded rather than joined with a delimiter, because
 * they come from splitting the model's `@@ACTIONS@@` line on "|" and are otherwise
 * free text — a delimiter would eventually meet its own delimiter and split one
 * label into two. These are the cases that would break a naive join.
 *
 * A JVM unit test: [ChatConverters] is pure logic over `org.json`, which the build
 * supplies a real implementation of for `src/test`.
 */
class ChatConvertersTest {

    private fun roundTrip(actions: List<String>) =
        ChatConverters.toActions(ChatConverters.fromActions(actions))

    @Test
    fun `round-trips ordinary labels`() {
        val actions = listOf("Call back", "Text her", "Ignore")
        assertEquals(actions, roundTrip(actions))
    }

    @Test
    fun `round-trips an empty list`() {
        assertEquals(emptyList<String>(), roundTrip(emptyList()))
    }

    @Test
    fun `survives labels containing the separator and quotes`() {
        val actions = listOf("""Reply "yes" | "no"""", "Snooze, 30m", "a\\b")
        assertEquals(actions, roundTrip(actions))
    }

    @Test
    fun `survives newlines and unicode`() {
        val actions = listOf("line\nbreak", "回复", "emoji 🎉")
        assertEquals(actions, roundTrip(actions))
    }

    /** A corrupt or legacy value must degrade to "no buttons", never crash the list. */
    @Test
    fun `malformed json decodes to an empty list`() {
        assertEquals(emptyList<String>(), ChatConverters.toActions("not json"))
        assertEquals(emptyList<String>(), ChatConverters.toActions(""))
    }
}
