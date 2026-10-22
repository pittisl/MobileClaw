package edu.pitt.isl.mobileclaw.notiftest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Redaction.sensitiveSpans] must return exactly the substrings [Redaction.redact]
 * removes. [MemoryPolicy] relies on that agreement: if every sensitive span is
 * user-attested, redaction would remove no unattested secret. Both operations must
 * walk the same patterns in the same order.
 */
class RedactionTest {

    @Test
    fun `spans are exactly what redaction removes`() {
        val samples = listOf(
            "your code is 445566",
            "card 4111 1111 1111 1111 charged",
            "SSN 123-45-6789 on file",
            "call 412-555-0100 or mail me@pitt.edu",
            "password: hunter2",
            "key XKJD-4729-QMVZ-1A2B",
            "nothing sensitive here at all",
        )
        for (s in samples) {
            // Removing each reported span by hand must reach the same place redact does.
            var byHand = s
            for (span in Redaction.sensitiveSpans(s)) {
                assertTrue("span \"$span\" not present in \"$byHand\"", byHand.contains(span))
                byHand = byHand.replaceFirst(span, Redaction.MARKER)
            }
            assertEquals(Redaction.redact(s), byHand)
        }
    }

    @Test
    fun `clean text has no spans`() {
        assertTrue(Redaction.sensitiveSpans("meet me at the library after lunch").isEmpty())
    }

    @Test
    fun `redaction behaviour is unchanged through ReasoningModule`() {
        // The documented chokepoint still works and still delegates here.
        val text = "OTP 445566 for me@pitt.edu"
        assertEquals(Redaction.redact(text), ReasoningModule.redact(text))
        assertFalse(ReasoningModule.redact(text).contains("445566"))
    }

    @Test
    fun `a year is not a PIN`() {
        // Pinned because the PIN pattern's lookahead is the kind of thing a refactor
        // silently drops, and memory content is full of dates.
        assertEquals("graduated in 2019", Redaction.redact("graduated in 2019"))
    }
}
