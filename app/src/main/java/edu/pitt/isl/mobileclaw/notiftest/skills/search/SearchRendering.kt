package edu.pitt.isl.mobileclaw.notiftest.skills.search

import edu.pitt.isl.mobileclaw.notiftest.Redaction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Turns search hits into the string the model reads. Pure JVM so the two
 * things that matter about it can be tested without an index: that the text is
 * **redacted as a whole** before it leaves, and that a hit is rendered as a path the
 * model can hand straight to `file_system`.
 *
 * Redaction covers the whole rendering, path and name included, rather than only the
 * snippet. A phone number in a file name is rarer than one in a body, but the rule
 * every other egress follows is "the string that leaves is scrubbed", and an exception
 * for paths is the kind that gets copied to the next tool.
 */
object SearchRendering {

    fun render(query: String, hits: List<FileIndex.Hit>, state: IndexState.Snapshot, note: String? = null): String {
        val sb = StringBuilder()
        note?.let { sb.append(it).append('\n') }
        if (hits.isEmpty()) {
            sb.append("No files match \"$query\".")
        } else {
            sb.append("${hits.size} file(s) match \"$query\" (best first):\n")
            hits.forEachIndexed { i, h ->
                sb.append("${i + 1}. ${h.name} — ${h.kind.label}, ${formatSize(h.sizeBytes)}, modified ${formatDate(h.modifiedMs)}\n")
                sb.append("   path: ${h.path}\n")
                if (h.kind == IndexPolicy.Kind.IMAGE) {
                    sb.append("   captured: ${h.capturedAtMs?.let(::formatCaptureDate) ?: "unknown"}")
                    h.capturedTimeSource?.let { sb.append(" ($it)") }
                    sb.append('\n')
                }
                h.snippet?.let { sb.append("   match: …${it.trim()}…\n") }
                // "looks like", not "contains": these are a classifier's guesses with their
                // scores, and the model should weigh them as such — text snippets are
                // the file's own words and get "match".
                h.labels?.takeIf { it.isNotEmpty() }?.let { sb.append("   looks like: ${IndexPolicy.labelSummary(it)}\n") }
                // A third wording for a third kind of evidence: this one is a similarity
                // score against the query's own description, with no word in common needed.
                h.similarity?.let { sb.append("   looks similar to \"$query\" (${String.format(Locale.ROOT, "%.2f", it)})\n") }
                // A fourth: text a recogniser read off pixels. Quoted at length rather than
                // as a snippet, because nothing can open a photo or a PDF to read the rest
                // — this excerpt is all the model will ever learn of what it says.
                h.ocrText?.let { text ->
                    val excerpt = IndexPolicy.ocrExcerpt(text)
                    sb.append("   text read from it by OCR (may contain misreadings): $excerpt\n")
                    if (excerpt.length < text.length) h.ocrSnippet?.let { sb.append("   OCR match: …${it.trim()}…\n") }
                }
            }
            sb.append(
                "Use file_system with operation \"cat\" and one of these paths to read a text file. " +
                    "Photos and PDFs cannot be read that way; the OCR text above is what is known of them."
            )
        }
        sb.append('\n').append(describe(state))
        return Redaction.redact(sb.toString())
    }

    /** One line about the index itself, so an empty result can be told from an empty index. */
    fun describe(state: IndexState.Snapshot, nowMs: Long = System.currentTimeMillis()): String = when {
        !state.hasBuilt ->
            "Index status: never built. Indexing runs in the background; the user can run it now from Settings › File search."
        state.error != null ->
            "Index status: last rebuild failed (${state.error}); results may be stale."
        else -> {
            val ago = (nowMs - state.builtAtMs).coerceAtLeast(0) / 60_000
            val when_ = if (ago < 1) "just now" else if (ago < 120) "$ago min ago" else "${ago / 60} h ago"
            val images = when {
                state.analysisOff -> ", photos and PDFs by name only: indexing them by content is off in Settings"
                state.imagesPending > 0 ->
                    ", ${state.imagesAnalysed} photos/PDFs indexed by content, ${state.imagesPending} not yet"
                state.imagesAnalysed > 0 -> ", ${state.imagesAnalysed} photos/PDFs indexed by content"
                else -> ""
            }
            // "full rebuild", not "built": photos and downloads added since are in the index
            // too (NewMediaWorker), and the counts here are the walk's, not the index's.
            "Index status: ${state.files} files (${state.withContent} with text content$images) at the last full " +
                "rebuild, $when_; new photos and downloads are added as they arrive."
        }
    }

    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} kB"
        else -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    fun formatDate(ms: Long): String =
        if (ms <= 0) "unknown" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(ms))

    /** Include the display offset so a local day is not mistaken for UTC or the EXIF camera zone. */
    fun formatCaptureDate(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm XXX", Locale.ROOT).format(Date(ms))
}
