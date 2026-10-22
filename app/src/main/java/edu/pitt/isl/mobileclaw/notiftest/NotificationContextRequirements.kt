package edu.pitt.isl.mobileclaw.notiftest

/**
 * Conservative source requirements for explicit document and personal-time questions.
 * These are lookup obligations, not extracted answers. A completed lookup can still
 * report no match or missing access; the model must then explain that or withhold a reply.
 * The English-only policy matches the rest of the agent.
 */
object NotificationContextRequirements {
    private val document = Regex("\\b(pdf|document|receipt|invoice|ticket|booking (?:code|confirmation|record)|confirmation (?:code|document))\\b", RegexOption.IGNORE_CASE)
    private val calendar = Regex("\\b(calendar|availability|free (?:slot|time)|schedule|appointment|meeting)\\b", RegexOption.IGNORE_CASE)
    private val personalPlan = Regex("\\b(my|our|we|i)\\b.{0,100}\\b(arriv(?:al|e|ing)|plan(?:ned)?|flight|trip)\\b", RegexOption.IGNORE_CASE)
    private val comparison = Regex("\\b(before|after|earlier|later|conflict|overlap)\\b", RegexOption.IGNORE_CASE)

    fun readersFor(rawText: String): Set<String> = buildSet {
        val text = rawText.replace(Regex("\\s+"), " ")
        if (document.containsMatchIn(text)) add("search_files")
        if (calendar.containsMatchIn(text) ||
            (personalPlan.containsMatchIn(text) && comparison.containsMatchIn(text))) add("read_calendar")
    }
}
