package edu.pitt.isl.mobileclaw.notiftest.skills.search

import edu.pitt.isl.mobileclaw.notiftest.FsPolicy
import java.io.File
import java.util.Locale

/**
 * What the file index may contain, decided without touching a device.
 *
 * Indexing decisions are kept apart from disk traversal so they can be tested on the JVM:
 *
 *  - **Which files get a document at all?** Every regular file under the root that is
 *    not under a skipped directory, subject to [FsPolicy.vet] — the same deny list the
 *    `file_system` tool is held to. Reusing it is the point: the index must never become
 *    a second, ungated route to a path the tool is refused, and a policy re-derived here
 *    would drift from the one over there.
 *  - **Which files get their *content* indexed?** Only text-like ones by extension, and
 *    only up to [MAX_CONTENT_CHARS]. Everything else is findable by name, path and date.
 *  - **Which files are *read by OCR*?** Decodable images and PDFs: the text
 *    the on-device recogniser finds on a photo, a screenshot or the first
 *    [PDF_OCR_PAGES] pages of a PDF goes into a field of its own, vetted by [keepText].
 *    Its own field and not `content`, for the reason labels have theirs: a recogniser's
 *    reading of pixels can be wrong in ways a file's own text cannot, and the rendering
 *    says which one the model is looking at.
 *  - **Which files get *labels*?** Decodable images within [MAX_LABEL_FILE_BYTES]
 *    are run through the on-device image classifier and the words it returns ("cat",
 *    "pet") become a searchable field of their own — a field, not `content`, because a
 *    machine's guess about a picture and a file's own text are different kinds of
 *    evidence and the model should be able to tell which it is reading. The rules for
 *    what a label has to be to count live here too, so the classifier is a source of
 *    candidates rather than the authority on what is indexed.
 *
 * Extensions rather than sniffing: a name is what the user remembers, and a sniffer
 * that reads the first bytes of every file on the card is most of the cost of indexing
 * for none of the benefit.
 */
object IndexPolicy {

    /** How a file is classed for the `kind` filter and for whether its body is read. */
    enum class Kind(val label: String) {
        TEXT("text"), IMAGE("image"), DOCUMENT("document"), AUDIO("audio"), VIDEO("video"), OTHER("other");

        companion object {
            /** The value the model may pass as `kind`; null for anything else (including "any"). */
            fun parse(value: String?): Kind? = value?.let { v -> entries.firstOrNull { it.label == v.lowercase(Locale.ROOT) } }
        }
    }

    /**
     * Content is read for these and nothing else. Deliberately no `.html`/`.xml`: markup
     * indexes as tag soup, and the small win on saved pages is not worth every browser's
     * cache directory matching "div".
     */
    private val TEXT_EXTENSIONS = setOf("txt", "md", "markdown", "csv", "tsv", "json", "log", "ini", "cfg", "conf", "yaml", "yml", "rst", "tex", "bib", "srt", "vtt")
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "tiff", "tif", "dng", "svg")
    private val DOCUMENT_EXTENSIONS = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "epub", "rtf")
    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "amr")
    private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "mov", "webm", "3gp", "avi")

    /**
     * The image formats `BitmapFactory` decodes on every supported API level. `svg` is
     * markup, `dng`/`tiff` are raw and unsupported by the platform decoder; they stay
     * findable by name and are never opened for labelling.
     */
    private val LABELLED_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp")

    /**
     * Directories never entered. `Android/` is every app's private external area (data,
     * obb, media caches — huge and not the user's files); the rest are trash, thumbnails
     * and hidden state. Matched by *name at any depth*, so a `.thumbnails` under DCIM is
     * skipped as well as one at the root.
     */
    private val SKIPPED_DIR_NAMES = setOf("Android", "LOST.DIR", ".thumbnails", ".trashed", ".Trash", ".cache", "cache", "node_modules")

    /** Most a single file contributes to the index; the rest of a long file is unseen. */
    const val MAX_CONTENT_CHARS = 64_000

    /** Files larger than this are never opened for content, whatever their extension. */
    const val MAX_CONTENT_FILE_BYTES = 4L * 1024 * 1024

    /** Ceiling on documents per build — a storm backstop, not a quota anyone should hit. */
    const val MAX_FILES_PER_BUILD = 20_000

    // ---- image labels ----

    /** One thing the classifier says is in a picture, with how sure it was. */
    data class Label(val name: String, val confidence: Float)

    /** Images larger than this are never decoded for labelling; a 40 MB panorama is not worth the heap. */
    const val MAX_LABEL_FILE_BYTES = 32L * 1024 * 1024

    /**
     * Below this a label is noise: the classifier's default threshold is 0.5, and at 0.5
     * a whiteboard photo comes back as "font" and "rectangle". Applied here, not only in
     * the classifier's options, so a fake labeller in a test is held to the same line.
     */
    const val LABEL_MIN_CONFIDENCE = 0.6f

    /** Most labels one image keeps. The tail of the classifier's list is the least sure part. */
    const val MAX_LABELS_PER_IMAGE = 10

    /**
     * Most images labelled *for the first time* in one rebuild. Labelling is the only
     * per-file work in the index that costs more than a read — a decode and an inference,
     * on the order of 100 ms — so a 5 000-photo card is not done in one background run
     * but over a few, each bounded, and the worker asks for another as long as some are
     * left. Already-labelled images cost nothing: their labels are carried across rebuilds.
     */
    const val MAX_NEW_LABELS_PER_BUILD = 500

    /**
     * Longest edge the image is decoded to before classification. The classifier's input
     * is 224 px; decoding a 12 MP photo whole to get there is most of the cost of
     * labelling and, for a HEIC on a mid-range phone, a real chance of running out of heap.
     */
    const val LABEL_TARGET_PX = 640

    /** Whether [name] and [sizeBytes] qualify for labelling: a decodable image within the cap. */
    fun indexesLabels(name: String, sizeBytes: Long): Boolean =
        extensionOf(name) in LABELLED_IMAGE_EXTENSIONS && sizeBytes in 1..MAX_LABEL_FILE_BYTES

    /**
     * Vets what the classifier returned: drops the unsure, keeps the surest
     * [MAX_LABELS_PER_IMAGE], lower-cases names so "Cat" and "cat" are one term, strips
     * anything that is not a word so a label can never carry punctuation into the
     * rendering, and folds duplicates to the higher score.
     */
    fun keepLabels(raw: List<Label>): List<Label> {
        val byName = LinkedHashMap<String, Float>()
        for (l in raw.sortedByDescending { it.confidence }) {
            if (l.confidence < LABEL_MIN_CONFIDENCE) continue
            val name = l.name.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N} ]+"), " ").replace(Regex("\\s+"), " ").trim()
            if (name.isEmpty()) continue
            if (name !in byName) byName[name] = l.confidence
            if (byName.size >= MAX_LABELS_PER_IMAGE) break
        }
        return byName.map { (n, c) -> Label(n, c) }
    }

    // ---- text read off images and PDFs (OCR) ----

    /** PDFs larger than this are never opened for OCR. */
    const val MAX_PDF_FILE_BYTES = 32L * 1024 * 1024

    /**
     * Pages of a PDF that are rendered and read. A boarding pass, a receipt or a ticket
     * says what it is on page one; a 200-page manual would cost seconds per rebuild for a
     * tail nobody searches by.
     */
    const val PDF_OCR_PAGES = 3

    /**
     * Longest edge an image or PDF page is rendered to for the recogniser. 640 px
     * ([LABEL_TARGET_PX]) is plenty for a 224-px classifier and illegible for text: on a
     * phone photo of a boarding pass the small print is a few pixels tall at that size. The
     * classifier and CLIP still see a [LABEL_TARGET_PX]-sized copy, so their inputs — and
     * the similarity threshold calibrated on them — are unchanged.
     */
    const val OCR_TARGET_PX = 1600

    /** Most text one file's OCR contributes. A page of dense print is ~3 000 characters. */
    const val MAX_OCR_CHARS = 8_000

    /**
     * Fewer letters or digits than this and the reading is noise — the recogniser finding
     * "|" in a fence or "O" in a plate — not text anyone would search for.
     */
    const val MIN_OCR_CHARS = 3

    /**
     * How much of a hit's OCR text the rendering quotes. The model cannot open a photo or
     * a PDF to read the rest (`file_system` reads bytes, and the follow-up turn has no
     * `file_system` at all), so this excerpt is the whole of what it learns about the
     * document; 600 characters holds a boarding pass or a receipt, and eight hits of it
     * stay well inside `ToolOutputBudget`.
     */
    const val OCR_EXCERPT_CHARS = 600

    /** Whether [name] and [sizeBytes] qualify for having their pages read: a PDF within the cap. */
    fun readsPdf(name: String, sizeBytes: Long): Boolean =
        extensionOf(name) == "pdf" && sizeBytes in 1..MAX_PDF_FILE_BYTES

    /** Whether the on-device models look at this file at all: a decodable image or a PDF. */
    fun analysesContent(name: String, sizeBytes: Long): Boolean =
        indexesLabels(name, sizeBytes) || readsPdf(name, sizeBytes)

    /**
     * Vets what the recogniser returned, as [keepLabels] does for the classifier: control
     * characters and whitespace runs collapse, the result is capped at [MAX_OCR_CHARS], and
     * a reading with fewer than [MIN_OCR_CHARS] letters or digits is dropped as noise.
     * Null means "nothing worth indexing", which the indexer still records as read.
     */
    fun keepText(raw: String?): String? {
        if (raw == null) return null
        val text = normaliseContent(raw).take(MAX_OCR_CHARS).trim()
        return text.takeIf { t -> t.count { it.isLetterOrDigit() } >= MIN_OCR_CHARS }
    }

    /** The start of [text] for the rendering, cut at a word boundary, with an ellipsis if cut. */
    fun ocrExcerpt(text: String): String {
        if (text.length <= OCR_EXCERPT_CHARS) return text
        val cut = text.lastIndexOf(' ', OCR_EXCERPT_CHARS).takeIf { it > OCR_EXCERPT_CHARS / 2 } ?: OCR_EXCERPT_CHARS
        return text.substring(0, cut).trimEnd() + " …"
    }

    // ---- image similarity ----

    /**
     * Minimum cosine score for the similarity route. This is a heuristic, not a
     * probability or proof that the image depicts the query. A false positive can make
     * the agent claim a photo exists, so results require user inspection and the
     * threshold should be evaluated against representative images and queries.
     */
    const val SIMILARITY_MIN = 0.19f

    /**
     * CLIP was trained on captions, and "a photo of a cat" lands nearer the photos than
     * the bare word does — the standard prompt trick. A query that already reads like a
     * caption is left alone.
     */
    fun similarityPrompt(query: String): String {
        val q = query.trim()
        return if (q.split(' ').size >= 4 || q.startsWith("a ", true) || q.startsWith("an ", true)) q else "a photo of $q"
    }

    /** How labels read in the tool's rendering: the word and the score, surest first. */
    fun labelSummary(labels: List<Label>): String =
        labels.joinToString(", ") { String.format(Locale.ROOT, "%s (%.2f)", it.name, it.confidence) }

    fun extensionOf(name: String): String {
        if (name.startsWith(".")) return ""
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        val ext = name.substring(dot + 1).lowercase(Locale.ROOT)
        return if (ext.length <= 8) ext else ""
    }

    fun kindOf(name: String): Kind = when (extensionOf(name)) {
        in TEXT_EXTENSIONS -> Kind.TEXT
        in IMAGE_EXTENSIONS -> Kind.IMAGE
        in DOCUMENT_EXTENSIONS -> Kind.DOCUMENT
        in AUDIO_EXTENSIONS -> Kind.AUDIO
        in VIDEO_EXTENSIONS -> Kind.VIDEO
        else -> Kind.OTHER
    }

    /** True for a directory the walk must not descend into. Hidden directories included. */
    fun skipsDirectory(name: String): Boolean = name in SKIPPED_DIR_NAMES || name.startsWith(".")

    /** Hidden files are skipped too; a dotfile is state, not something the user is looking for. */
    fun skipsFile(name: String): Boolean = name.startsWith(".")

    /**
     * Whether [canonicalPath] may be indexed at all. Delegates to [FsPolicy] so a path the
     * `file_system` tool is refused is refused here for the same reason — and returns that
     * reason, so the indexer can log why a subtree was passed over instead of silently
     * producing a smaller index.
     */
    fun readDenial(canonicalPath: String): String? =
        (FsPolicy.vet(canonicalPath, FsPolicy.Access.READ) as? FsPolicy.Verdict.Deny)?.reason

    /**
     * Loosens an unsuccessful multiword query to match any of its words; returns null
     * when fewer than two words remain. AppSearch normally ANDs plain query terms,
     * so contextual words absent from the file can otherwise hide a useful result.
     *
     * Operators, quotes and punctuation are dropped. Ranking puts files matching more
     * terms first, and the fallback remains a search for the original words.
     */
    fun anyWordQuery(query: String): String? {
        val words = Regex("[\\p{L}\\p{N}]+").findAll(query).map { it.value }
            .filter { it.length >= 2 && it.uppercase(Locale.ROOT) !in QUERY_OPERATORS }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .toList()
        return if (words.size < 2) null else words.joinToString(" OR ")
    }

    private val QUERY_OPERATORS = setOf("AND", "OR", "NOT")

    /** Most files one incremental [FileIndexer.add] takes; a burst beyond it waits for the rebuild. */
    const val MAX_FILES_PER_ADD = 200

    /**
     * Whether a single file at [canonicalPath] is one the walk from [rootCanonical] would
     * have indexed: under the root, no skipped or hidden directory between them, not a
     * hidden file, and not refused by [readDenial]. The incremental path asks this instead
     * of walking, and it has to agree with the walk exactly — a file admitted here and
     * skipped there would appear in the index and vanish at the next rebuild.
     */
    fun admitsUnderRoot(canonicalPath: String, rootCanonical: String): Boolean {
        val prefix = rootCanonical.trimEnd(File.separatorChar) + File.separatorChar
        if (!canonicalPath.startsWith(prefix)) return false
        val parts = canonicalPath.removePrefix(prefix).split(File.separatorChar)
        if (parts.any { it.isEmpty() }) return false
        if (parts.dropLast(1).any { skipsDirectory(it) } || skipsFile(parts.last())) return false
        return readDenial(canonicalPath) == null
    }

    /** Whether [name] and [sizeBytes] qualify for content extraction (text only; images get labels instead). */
    fun indexesContent(name: String, sizeBytes: Long): Boolean =
        kindOf(name) == Kind.TEXT && sizeBytes in 1..MAX_CONTENT_FILE_BYTES

    /**
     * Truncates and normalises a file body for the index: NULs and control characters go
     * (a binary file with a `.txt` name is the usual reason they are there), runs of
     * whitespace collapse, and the result is capped at [MAX_CONTENT_CHARS].
     */
    fun normaliseContent(raw: String): String {
        val cleaned = StringBuilder(minOf(raw.length, MAX_CONTENT_CHARS))
        var lastWasSpace = false
        for (ch in raw) {
            if (cleaned.length >= MAX_CONTENT_CHARS) break
            val isSpace = ch.isWhitespace()
            when {
                ch == '\u0000' || (ch.isISOControl() && !isSpace) -> continue
                isSpace -> { if (!lastWasSpace) cleaned.append(' '); lastWasSpace = true }
                else -> { cleaned.append(ch); lastWasSpace = false }
            }
        }
        return cleaned.toString().trim()
    }

    /**
     * Document id for a path. AppSearch ids are per-namespace strings with a length cap,
     * and paths on a card run well past it, so the id is a hash of the canonical path and
     * the path itself is a stored property. Stable across builds — the same file gets the
     * same id, which is what lets a rebuild overwrite rather than duplicate.
     */
    fun documentId(canonicalPath: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        return md.digest(canonicalPath.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /**
     * The searchable "name" text for a file: its base name with separators opened up, so
     * `hotel_receipt-austin.pdf` matches "hotel receipt" and "austin". The original name is
     * stored separately for display.
     */
    fun nameTokens(name: String): String =
        name.replace(Regex("[._\\-\\[\\]()+,]+"), " ").replace(Regex("\\s+"), " ").trim()

    /** Path text for the index: directory components as words, without the root prefix. */
    fun pathTokens(canonicalPath: String, rootCanonical: String): String {
        val rel = if (canonicalPath.startsWith(rootCanonical)) canonicalPath.removePrefix(rootCanonical) else canonicalPath
        return rel.split(File.separatorChar).filter { it.isNotBlank() }.dropLast(1).joinToString(" ") { nameTokens(it) }
    }
}
