package edu.pitt.isl.mobileclaw.notiftest

/**
 * The patterns that define "sensitive", and the two questions anyone asks about them:
 * what does this text look like with the secrets removed ([redact]), and which secrets
 * were in it ([sensitiveSpans]).
 *
 * [ReasoningModule.redact] is the common redaction entry point. Memory provenance checks
 * need the *spans*, not the redacted string, and duplicating the regexes to get them
 * would make "sensitive" mean two different things in two files. It also has no Android
 * imports, so the hostile cases are JVM-testable, same as [FsPolicy] and
 * [ShellCommandPolicy].
 *
 * [redact] applies the patterns in sequence, each over the output of the last, and
 * [sensitiveSpans] walks the identical sequence collecting what each one matched. That
 * is deliberate rather than incidental: the spans are exactly the substrings [redact]
 * would have removed, so a memory whose every span is attested is a memory that redaction
 * would not have touched. Two independent scans would drift the first time a pattern was
 * added.
 */
object Redaction {

    const val MARKER = "[REDACTED]"

    // 6-digit standalone numbers — OTP / 2FA codes
    private val PATTERN_OTP = Regex("""\b\d{6}\b""")
    // Formatted card numbers: 1234 5678 9012 3456 or 1234-5678-9012-3456
    private val PATTERN_CARD_GROUPED = Regex("""\b\d{4}[\s\-]\d{4}[\s\-]\d{4}[\s\-]\d{4}\b""")
    // Unformatted runs of 13–19 digits (raw card numbers)
    private val PATTERN_CARD_RAW = Regex("""\b\d{13,19}\b""")
    // Email addresses
    private val PATTERN_EMAIL = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    // License/activation keys: 3+ hyphen-separated blocks of uppercase alphanumeric (e.g. XKJD-4729-QMVZ-1A2B)
    private val PATTERN_LICENSE_KEY = Regex("""\b[A-Z0-9]{4,8}(?:-[A-Z0-9]{4,8}){2,}\b""")
    // 4-8 digit PINs/passcodes; skips years (19xx/20xx) and currency-prefixed amounts
    private val PATTERN_PIN = Regex("""(?<![€£¥$])\b(?!(?:19|20)\d{2}\b)\d{4,8}\b""")
    // US phone numbers: 555-867-5309, (555) 867-5309, +1 555 867 5309
    private val PATTERN_PHONE_US = Regex("""\b(?:\+?1[-.\s]?)?\(?[2-9]\d{2}\)?[-.\s]\d{3}[-.\s]\d{4}\b""")
    // Social Security Numbers: 123-45-6789 or 123 45 6789
    private val PATTERN_SSN = Regex("""\b\d{3}[-\s]\d{2}[-\s]\d{4}\b""")
    // Inline credential leaks: "password: hunter2", "token=abc123"
    private val PATTERN_CREDENTIAL = Regex("""(?i)(?:password|passwd|pwd|secret|token|api[_\-]?key|auth[_\-]?key|passcode)\s*[:=]\s*\S+""")

    /**
     * Order is load-bearing and is the order [ReasoningModule.redact] has always used:
     * broader shapes first, so a grouped card number is consumed as one span rather than
     * being picked apart by the PIN rule afterwards.
     */
    private val PATTERNS = listOf(
        PATTERN_CARD_GROUPED,
        PATTERN_CARD_RAW,
        PATTERN_SSN,
        PATTERN_CREDENTIAL,
        PATTERN_PHONE_US,
        PATTERN_EMAIL,
        PATTERN_LICENSE_KEY,
        PATTERN_OTP,
        PATTERN_PIN,
    )

    /**
     * Redacts OTPs, card/SSN/phone numbers, emails, license keys, credentials and PINs.
     * Reached through [ReasoningModule.redact], which is the name every call site uses.
     */
    fun redact(text: String): String = scan(text, null)

    /**
     * Every substring [redact] would replace, in the order the patterns run.
     *
     * Used by the memory provenance check to ask whether the *secrets* in a proposed memory
     * are ones the user typed. Returns an empty list for text with nothing sensitive in
     * it, which is the common case and means "there is nothing here to protect".
     */
    fun sensitiveSpans(text: String): List<String> =
        mutableListOf<String>().also { scan(text, it) }

    private fun scan(text: String, collect: MutableList<String>?): String {
        var s = text
        for (p in PATTERNS) {
            if (collect != null) p.findAll(s).forEach { collect += it.value }
            s = s.replace(p, MARKER)
        }
        return s
    }
}
