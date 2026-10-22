package edu.pitt.isl.mobileclaw.notiftest

/**
 * Extracts the outermost JSON object from a model reply.
 *
 * Even when `response_format: json_object` is requested, compatible servers may wrap
 * JSON in a Markdown fence or surrounding prose. Taking the outermost braces accepts
 * those wrappers without trying to repair the JSON inside. Malformed content still
 * fails parsing and leaves triage on its fallback path.
 *
 * Pure JVM so [LlmJsonTest] can cover these reply shapes without a model server.
 */
object LlmJson {
    fun extractObject(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else text
    }
}
