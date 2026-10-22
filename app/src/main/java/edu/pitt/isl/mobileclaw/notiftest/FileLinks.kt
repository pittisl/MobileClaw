package edu.pitt.isl.mobileclaw.notiftest

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.View

/**
 * Turns absolute shared-storage paths in rendered text into tappable links.
 *
 * The model answers a file question with the path `search_files` returned
 * ("…IMG_20260912_101512.jpg in /storage/emulated/0/DCIM/Camera/"), and a path the user
 * can read but not tap is a dead end on a phone. This is a *display* affordance, not a
 * tool: the tap is the user's own gesture, nothing goes through the model or the confirm
 * gate, and what happens on tap is [FileOpener]'s read-only `ACTION_VIEW`.
 *
 * Two shapes are matched, because the model uses both: a full path
 * (`/storage/emulated/0/DCIM/Camera/IMG_1.jpg`) and a bare file name with a known
 * extension (`IMG_1.jpg`, "located in …/Camera/"). Full paths are linked only if they
 * exist, so a hallucinated path stays plain text; bare names are resolved when tapped
 * ([FileOpener.open] joins them with directories named in the same message, then asks
 * `MediaStore`), so the bind does no I/O per name. [findPaths] is pure so the matching
 * can be held to examples on the JVM.
 */
object FileLinks {

    /**
     * An absolute path under shared storage: `/storage/emulated/<user>/…` or `/sdcard/…`.
     * Segments stop at whitespace, quotes, backticks and Markdown/bracket punctuation;
     * trailing sentence punctuation is trimmed afterwards ("…/IMG_1.jpg." ends a sentence,
     * it is not part of the name). Spaces in names are not matched — the model rarely
     * emits them and a looser pattern swallows the rest of the sentence.
     */
    private val PATH_RE = Regex("""(?:/storage/emulated/\d+|/sdcard)(?:/[^\s`'"<>|*?()\[\]]+)*""")
    /** A bare name with an extension the index knows: photos, media, notes, documents. */
    private val NAME_RE = Regex("""(?<![\w/.\-])[\w\-][\w.\-]*\.(?:jpe?g|png|gif|webp|heic|heif|bmp|mp4|mkv|mov|mp3|m4a|wav|pdf|docx?|xlsx?|pptx?|md|markdown|txt|csv|tsv|json|log)\b""", RegexOption.IGNORE_CASE)
    private const val TRAILING = ".,;:!"

    enum class Kind { PATH, NAME }

    data class Hit(val start: Int, val end: Int, val kind: Kind) {
        fun of(text: CharSequence): String = text.substring(start, end)
    }

    /**
     * Every path- or name-shaped span in [text], in order. Full paths are trimmed of
     * trailing punctuation; a name inside a full path is not reported twice.
     */
    fun findPaths(text: CharSequence): List<Hit> {
        val out = ArrayList<Hit>()
        for (m in PATH_RE.findAll(text)) {
            var end = m.range.last + 1
            while (end > m.range.first && text[end - 1] in TRAILING) end--
            // A bare "/sdcard" or a path ending in "/" names a directory; skip it.
            val s = text.substring(m.range.first, end)
            if (s.endsWith("/") || s.count { it == '/' } < 2) continue
            out.add(Hit(m.range.first, end, Kind.PATH))
        }
        for (m in NAME_RE.findAll(text)) {
            val start = m.range.first; val end = m.range.last + 1
            if (out.any { it.kind == Kind.PATH && start >= it.start && end <= it.end }) continue
            out.add(Hit(start, end, Kind.NAME))
        }
        out.sortBy { it.start }
        return out
    }

    /** Directories named anywhere in [text] (full paths, trailing slash or not), for name resolution. */
    fun directoriesIn(text: CharSequence): List<String> =
        PATH_RE.findAll(text).map { it.value.trimEnd(*TRAILING.toCharArray()) }
            .map { if (it.endsWith("/")) it.dropLast(1) else it.substringBeforeLast('/') }
            .filter { it.count { c -> c == '/' } >= 2 }.distinct().toList()

    /**
     * Adds a [ClickableSpan] over each existing path in [sb]. [exists] is injected so the
     * adapter decides what "exists" means (a `File.exists()` on the UI thread is fine for a
     * handful of paths in one bubble).
     */
    fun linkify(sb: SpannableStringBuilder, exists: (String) -> Boolean, onOpen: (String) -> Unit): SpannableStringBuilder {
        for (hit in findPaths(sb)) {
            val target = hit.of(sb)
            if (hit.kind == Kind.PATH && !exists(target)) continue
            sb.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) = onOpen(target)
            }, hit.start, hit.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }
}
