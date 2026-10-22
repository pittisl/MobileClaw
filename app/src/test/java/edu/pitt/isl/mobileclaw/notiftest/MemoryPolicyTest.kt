package edu.pitt.isl.mobileclaw.notiftest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The memory trust policy threat model, as tests. Pure JVM — no device, no database, no skill.
 *
 * The property every case here is circling: **a hostile notification cannot put text into
 * the current USER turn**, so it cannot pass either check, no matter what provenance it
 * talks the model into declaring. Everything else is about the separation of provenance and redaction — the model may
 * rephrase around a secret the user typed, and may never introduce one.
 */
class MemoryPolicyTest {

    private fun evidence(userText: String) = UserTurnEvidence(userText)

    // ---- The carve-out actually working ----

    @Test
    fun `paraphrase around a user-typed number keeps the number`() {
        val ev = evidence("remember priya's number: 412-555-0100")
        val w = MemoryPolicy.classify("Priya's phone number is (412) 555-0100", ev)
        // Unredacted: every secret in it is one the user typed.
        assertFalse(w.redacted)
        assertTrue(w.content.contains("555-0100"))
        // But not USER_STATED: those are not the user's words. This is the separation of provenance and redaction —
        // tied together, this case would have redacted the user's own phone number.
        assertEquals(MemoryProvenance.MODEL_INFERRED, w.provenance)
    }

    @Test
    fun `verbatim quote earns the trusted label as well`() {
        val ev = evidence("Remember that Priya's number is 412-555-0100, she's my advisor")
        val w = MemoryPolicy.classify("priya's number is 412-555-0100", ev)
        assertEquals(MemoryProvenance.USER_STATED, w.provenance)
        assertFalse(w.redacted)
    }

    @Test
    fun `formatting differences in the secret do not matter`() {
        // The user typed the digits; how the model punctuates them is not a new secret.
        val ev = evidence("my number is (412) 555-0100 and my email is me@pitt.edu")
        val w = MemoryPolicy.classify("User: 412.555.0100, ME@PITT.EDU", ev)
        assertFalse(w.redacted)
    }

    // ---- The escalation user-attested memory exists to prevent ----

    @Test
    fun `a secret the user never typed is redacted and untrusted`() {
        // The injection chain: notification text reaches the model and the model is
        // persuaded to store it. There is no provenance argument to lie about, so the
        // only thing left for an injection to do is get the content past the evidence.
        val ev = evidence("what's on my phone?")
        val w = MemoryPolicy.classify("the user's backup code is 998877", ev)
        assertEquals(MemoryProvenance.MODEL_INFERRED, w.provenance)
        assertTrue(w.redacted)
        assertFalse(w.content.contains("998877"))
    }

    @Test
    fun `one attested secret does not launder an unattested one`() {
        val ev = evidence("remember my number 412-555-0100")
        val w = MemoryPolicy.classify("number 412-555-0100 and card 4111 1111 1111 1111", ev)
        // attestsSensitiveSpans is an `all`, not an `any` — the whole content is redacted
        // rather than the offending span alone, because partial redaction would have to
        // decide which span is which and gets that wrong under overlap.
        assertTrue(w.redacted)
        assertFalse(w.content.contains("4111"))
    }

    @Test
    fun `no evidence fails closed`() {
        val w = MemoryPolicy.classify("the OTP is 445566", null)
        assertEquals(MemoryProvenance.MODEL_INFERRED, w.provenance)
        assertTrue(w.redacted)
    }

    @Test
    fun `a tool write can never land in the daily-summary tier`() {
        // the daily pass owns that tier, writes it transactionally with no user turn in scope, and
        // does not come through classify at all. Nothing a tool does reaches it — pinned
        // here because the daily pass treats that label differently from the other two.
        val ev = evidence("summarise today for me")
        val w = MemoryPolicy.classify("today the agent dismissed 4 notifications", ev)
        assertEquals(MemoryProvenance.MODEL_INFERRED, w.provenance)
    }

    @Test
    fun `an old user turn is not consent for this one`() {
        // The evidence is built from the *newest* user message in OpenAiAiClient. This
        // pins the consequence: a secret from an earlier turn is no longer attested.
        val ev = evidence("thanks, that's helpful")
        val w = MemoryPolicy.classify("my SSN is 123-45-6789", ev)
        assertTrue(w.redacted)
    }

    // ---- Boundaries of the matching rules ----

    @Test
    fun `content with nothing sensitive is stored as written`() {
        val ev = evidence("I like my coffee black")
        val w = MemoryPolicy.classify("The user prefers black coffee", ev)
        assertFalse(w.redacted)
        assertEquals("The user prefers black coffee", w.content)
    }

    @Test
    fun `empty content is never attested as user-stated`() {
        // "".contains("") is true, so an unguarded verbatim check would hand the trusted
        // label to an empty memory.
        val ev = evidence("hello")
        assertFalse(evidence("hello").attestsVerbatim(""))
        assertEquals(
            MemoryProvenance.MODEL_INFERRED,
            MemoryPolicy.classify("   ", ev).provenance
        )
    }

    @Test
    fun `whitespace and case do not break the verbatim check`() {
        val ev = evidence("Remember:  I  prefer\naisle seats")
        assertTrue(ev.attestsVerbatim("i prefer aisle seats"))
    }

    @Test
    fun `verbatim check is containment, not equality`() {
        // The user's turn carries an instruction around the fact ("remember that ..."),
        // so requiring equality would reject every real phrasing.
        val ev = evidence("please remember that I am allergic to peanuts")
        assertTrue(ev.attestsVerbatim("I am allergic to peanuts"))
    }
}
