package edu.pitt.isl.mobileclaw.notiftest

/**
 * Where a memory came from. Stored on every row, rendered into every prompt
 * that carries one, and dispatched on by the daily pass, so it is not a
 * label — it decides what may be deleted, what may be trusted, and how the row reads to
 * the model.
 *
 * It is **verified, never declared**. [MemoryPolicy] assigns provenance from user evidence; the model cannot choose it.
 */
enum class MemoryProvenance {
    /** The user said this, in their own words, in the turn that wrote it. */
    USER_STATED,

    /** The model decided it was worth keeping. May derive from notification text. */
    MODEL_INFERRED,

    /** Written by the daily pass, about one calendar day. Never reachable by a tool. */
    DAILY_SUMMARY;

    companion object {
        /** Tolerant read for rows written by a build with a constant this one lacks. */
        fun fromString(s: String?): MemoryProvenance =
            entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: MODEL_INFERRED
    }
}

/**
 * What the user actually typed this turn — the only thing that can attest a memory.
 *
 * Deliberately exposes no accessor for the text itself, only the two yes/no questions
 * [MemoryPolicy] needs. It travels through the agent loop inside an [AgentTurn], where
 * anything downstream could reach it; a carrier that could hand back the user's raw
 * message would be a new way to read user input, which is not what this is for.
 */
class UserTurnEvidence(userText: String) {

    /** Whitespace/case-normalised, for the verbatim check. */
    private val prose = normaliseProse(userText)

    /** Alphanumerics only, for the span check — see [attestsSensitiveSpans]. */
    private val alnum = normaliseAlnum(userText)

    /**
     * Whether [content] is the user's own words, allowing only for whitespace and case.
     *
     * This is the strict half, and it gates the [MemoryProvenance.USER_STATED] *label* —
     * which carries deletion protection and reads to the model as something the
     * user said. A paraphrase is not something the user said, so it does not get
     * the label. It may still be stored unredacted; see [attestsSensitiveSpans].
     */
    fun attestsVerbatim(content: String): Boolean {
        val needle = normaliseProse(content)
        return needle.isNotEmpty() && prose.contains(needle)
    }

    /**
     * Whether every secret in [content] is one the user typed this turn.
     *
     * This is the half that gates **redaction**. The model is allowed to
     * rephrase — "Priya's phone number is (412) 555-0100" for a user who typed
     * "remember priya's number: 412-555-0100" — because rephrasing the prose around a
     * secret does not put a secret there. What it cannot do is introduce one: a number
     * that reached the model from a notification is not in [alnum], so it is redacted
     * whatever the tool call claimed.
     *
     * Comparison is on alphanumerics only, so `(412) 555-0100` and `412-555-0100` are the
     * same secret. Content with nothing sensitive in it is vacuously attested — there is
     * nothing to protect, and [Redaction.redact] would be a no-op on it anyway.
     */
    fun attestsSensitiveSpans(content: String): Boolean =
        Redaction.sensitiveSpans(content).all { span ->
            val needle = normaliseAlnum(span)
            // A span that normalises away entirely would be contained by anything;
            // fail closed rather than let it attest itself.
            needle.isNotEmpty() && alnum.contains(needle)
        }

    private companion object {
        val WHITESPACE = Regex("""\s+""")
        val NON_ALNUM = Regex("""[^a-z0-9]+""")

        fun normaliseProse(s: String) = s.lowercase().replace(WHITESPACE, " ").trim()
        fun normaliseAlnum(s: String) = s.lowercase().replace(NON_ALNUM, "")
    }
}

/**
 * The outcome of [MemoryPolicy.classify]: what actually gets written.
 *
 * [content] is what lands on disk, [provenance] is the verified label, and [redacted]
 * says whether the two differ — which the memory review screen needs, because "we kept your
 * phone number" and "we replaced it with [REDACTED]" are different things to show a user
 * about a row they asked for.
 */
data class MemoryWrite(
    val provenance: MemoryProvenance,
    val content: String,
    val redacted: Boolean
)

/**
 * Classifies a proposed memory using evidence from the current user turn.
 * Pure JVM so hostile-input cases need no device, database or skill.
 *
 * Two independent checks fail closed:
 * - The USER_STATED label requires the user's own words, checked by
 *   [UserTurnEvidence.attestsVerbatim]. It receives deletion protection and is
 *   rendered as user-stated in prompts.
 * - Unredacted storage requires every sensitive span to be user-attested, checked by
 *   [UserTurnEvidence.attestsSensitiveSpans]. A paraphrase can pass this check without
 *   receiving the USER_STATED label.
 *
 * This separation preserves a phone number the user supplied even if the surrounding
 * prose was paraphrased, while keeping that paraphrase inferred and deletable. A hostile
 * notification cannot supply evidence in the current USER turn and therefore cannot
 * bypass either check by declaring its own provenance.
 */
object MemoryPolicy {

    /**
     * **There is no `claimed` parameter, and that is the strongest form of the rule.**
     * The memory tool's schema has no provenance field at all, so the model never states
     * one — the evidence decides, and an injection has nothing to say. An earlier draft
     * took the model's claim and verified it; the claim turned out to do no work, because
     * a claim that is never believed without evidence is exactly a claim that need not be
     * made.
     *
     * @param rawContent the memory as the model wrote it.
     * @param evidence the current turn's user message, or null when there isn't one
     *   (no [AgentTurn] installed, or a caller outside the chat loop). Null fails closed:
     *   inferred and redacted.
     */
    fun classify(rawContent: String, evidence: UserTurnEvidence?): MemoryWrite {
        // Two outcomes only. DAILY_SUMMARY therefore falls out here as MODEL_INFERRED
        // **by construction** rather than by a guard — the daily pass owns that tier, writes
        // it transactionally alongside the supersedes it applies, and does not come
        // through this function at all. Worth stating: an explicit downgrade branch was
        // written first and deleted, because it could not be made to fail.
        val provenance = if (evidence?.attestsVerbatim(rawContent) == true) {
            MemoryProvenance.USER_STATED
        } else {
            MemoryProvenance.MODEL_INFERRED
        }

        val keepVerbatim = evidence?.attestsSensitiveSpans(rawContent) == true
        val content = if (keepVerbatim) rawContent else Redaction.redact(rawContent)

        return MemoryWrite(
            provenance = provenance,
            content = content,
            redacted = content != rawContent
        )
    }
}
