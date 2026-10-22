package edu.pitt.isl.mobileclaw.notiftest.skills.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What `search_files` hands the model: the two properties an index cannot
 * show on its own — that the rendering is redacted before it leaves, and that an empty
 * result is distinguishable from an index that was never built.
 */
class SearchRenderingTest {

    private val built = IndexState.Snapshot(builtAtMs = 1_000L, files = 42, withContent = 7, denied = 0, durationMs = 300, error = null)
    private val never = IndexState.Snapshot(builtAtMs = 0L, files = 0, withContent = 0, denied = 0, durationMs = 0, error = null)

    private fun hit(
        name: String, path: String, snippet: String?, labels: List<IndexPolicy.Label>? = null, similarity: Float? = null,
        ocrText: String? = null, ocrSnippet: String? = null,
    ) = FileIndex.Hit(name, path, IndexPolicy.kindOf(name), 1234, 1_700_000_000_000L, snippet, labels, similarity, ocrText, ocrSnippet)

    /** What the recogniser reads off a boarding pass, in the shape the demo uses. */
    private val boardingPass =
        "BOARDING PASS UNITED Passenger YANG/BOYUAN Flight UA1234 From PIT Pittsburgh To AUS Austin " +
            "Date 27OCT Boarding 06:35 Departs 07:15 Gate B12 Seat 14C Ticket 0162345678901"

    @Test
    fun `a secret in a snippet does not reach the model`() {
        val out = SearchRendering.render(
            "card", listOf(hit("wallet.txt", "/storage/emulated/0/Documents/wallet.txt", "my card is 4111 1111 1111 1111 exp 12/27")), built
        )
        assertFalse("card number survived rendering", out.contains("4111 1111 1111 1111"))
        assertTrue("the rest of the line survives", out.contains("my card is"))
    }

    @Test
    fun `a secret in a file name is scrubbed as well`() {
        // Rarer than a body, but the rule is "the string that leaves is scrubbed", with no
        // exception for the parts the model is expected to copy into the next tool call.
        val out = SearchRendering.render("otp", listOf(hit("otp 483920.txt", "/storage/emulated/0/otp 483920.txt", null)), built)
        assertFalse(out.contains("483920"))
    }

    @Test
    fun `a hit renders as a path the model can hand to file_system`() {
        val out = SearchRendering.render("budget", listOf(hit("budget-2026.md", "/storage/emulated/0/Documents/budget-2026.md", "Q3 budget review")), built)
        assertTrue(out.contains("path: /storage/emulated/0/Documents/budget-2026.md"))
        assertTrue(out.contains("match: …Q3 budget review…"))
        assertTrue(out.contains("file_system"))
        assertTrue(out.contains("text, 1 kB"))
    }

    @Test
    fun `a labelled photo says what it looks like, an unlabelled one says nothing`() {
        val cat = hit("IMG_0042.jpg", "/storage/emulated/0/DCIM/Camera/IMG_0042.jpg", null,
            listOf(IndexPolicy.Label("cat", 0.93f), IndexPolicy.Label("pet", 0.88f)))
        val unlabelled = hit("IMG_0043.jpg", "/storage/emulated/0/DCIM/Camera/IMG_0043.jpg", null, null)
        val nothingSeen = hit("IMG_0044.jpg", "/storage/emulated/0/DCIM/Camera/IMG_0044.jpg", null, emptyList())
        val out = SearchRendering.render("cat", listOf(cat, unlabelled, nothingSeen), built)
        assertTrue(out, out.contains("looks like: cat (0.93), pet (0.88)"))
        assertEquals("exactly one hit carries labels", 1, out.split("looks like:").size - 1)
        // Guesses are not quotes: a label line must never borrow the text snippet's wording.
        assertFalse(out.contains("match:"))
    }

    @Test
    fun `a similarity hit says what it resembles and how much, in a third wording`() {
        val cat = hit("IMG_0042.jpg", "/storage/emulated/0/DCIM/Camera/IMG_0042.jpg", null,
            listOf(IndexPolicy.Label("cat", 0.93f)), similarity = 0.271f)
        val out = SearchRendering.render("orange cat", listOf(cat), built)
        assertTrue(out, out.contains("looks similar to \"orange cat\" (0.27)"))
        assertTrue(out, out.contains("looks like: cat (0.93)"))
        assertFalse(out.contains("match:"))
    }

    @Test
    fun `the status line counts analysed and pending photos only when there are any`() {
        assertFalse(SearchRendering.describe(built).contains("indexed by content"))
        val some = built.copy(imagesAnalysed = 12)
        assertTrue(SearchRendering.describe(some).contains("12 photos/PDFs indexed by content"))
        assertFalse(SearchRendering.describe(some).contains("not yet"))
        val owed = built.copy(imagesAnalysed = 12, imagesPending = 3)
        assertTrue(SearchRendering.describe(owed).contains("3 not yet"))
        // Off is neither "indexed" nor "owed": the model should stop expecting photo content.
        val off = built.copy(analysisOff = true)
        assertTrue(SearchRendering.describe(off).contains("is off in Settings"))
        assertFalse(SearchRendering.describe(off).contains("not yet"))
    }

    // ---- OCR ----

    @Test
    fun `text read off a photo is quoted in a fourth wording, apart from the other three`() {
        val pass = hit("Screenshot_boarding.png", "/storage/emulated/0/Pictures/Screenshot_boarding.png", null,
            emptyList(), ocrText = boardingPass, ocrSnippet = "Flight UA1234 From PIT")
        val out = SearchRendering.render("UA1234", listOf(pass), built)
        assertTrue(out, out.contains("text read from it by OCR (may contain misreadings): BOARDING PASS UNITED"))
        // The excerpt is what the model answers from: the departure time has to be in it.
        assertTrue(out, out.contains("Departs 07:15"))
        assertTrue(out, out.contains("Gate B12 Seat 14C"))
        // Short enough to quote whole, so no separate match line repeating part of it.
        assertFalse(out.contains("OCR match:"))
        // A recogniser's reading is neither the file's own text nor a classifier's guess.
        assertFalse(out.contains("match: …"))
        assertFalse(out.contains("looks like:"))
    }

    @Test
    fun `a secret in OCR text is scrubbed while the flight survives`() {
        val pass = hit("boarding.pdf", "/storage/emulated/0/Download/boarding.pdf", null, null, ocrText = boardingPass)
        val out = SearchRendering.render("boarding pass", listOf(pass), built)
        assertFalse("the 13-digit ticket number reached the model", out.contains("0162345678901"))
        assertTrue(out, out.contains("UA1234"))
        assertTrue(out, out.contains("PIT Pittsburgh To AUS Austin"))
    }

    @Test
    fun `long OCR text is excerpted, and the match is quoted when the excerpt misses it`() {
        val filler = (1..200).joinToString(" ") { "word$it" }
        val long = "$filler the confirmation code is LKVQ7X at the end"
        val doc = hit("manual.pdf", "/storage/emulated/0/Documents/manual.pdf", null, null,
            ocrText = long, ocrSnippet = "confirmation code is LKVQ7X")
        val out = SearchRendering.render("confirmation", listOf(doc), built)
        val line = out.lines().first { it.contains("text read from it by OCR") }
        assertTrue(line, line.endsWith(" …"))
        assertTrue("excerpt stays within its cap", line.length < IndexPolicy.OCR_EXCERPT_CHARS + 80)
        assertTrue(out, out.contains("OCR match: …confirmation code is LKVQ7X…"))
    }

    @Test
    fun `the closing line does not send the model to cat a photo or a PDF`() {
        val out = SearchRendering.render("pass", listOf(hit("pass.pdf", "/storage/emulated/0/pass.pdf", null, ocrText = boardingPass)), built)
        assertTrue(out, out.contains("Photos and PDFs cannot be read that way"))
    }

    @Test
    fun `no hits on a built index is a miss, not a missing index`() {
        val out = SearchRendering.render("zzz", emptyList(), built)
        assertTrue(out.startsWith("No files match \"zzz\"."))
        assertTrue(out.contains("42 files (7 with text content)"))
        assertFalse(out.contains("never built"))
    }

    @Test
    fun `no hits on an unbuilt index says so and points at Settings`() {
        val out = SearchRendering.render("zzz", emptyList(), never)
        assertTrue(out.contains("never built"))
        assertTrue(out.contains("Settings"))
    }

    @Test
    fun `a failed rebuild is reported as staleness rather than hidden`() {
        val failed = built.copy(error = "index write failed: disk full")
        assertTrue(SearchRendering.describe(failed).contains("disk full"))
    }

    @Test
    fun `sizes and dates are human`() {
        assertEquals("512 B", SearchRendering.formatSize(512))
        assertEquals("2 kB", SearchRendering.formatSize(2_500))
        assertEquals("1.5 MB", SearchRendering.formatSize(1_572_864))
        assertEquals("unknown", SearchRendering.formatDate(0))
    }

    @Test
    fun `capture dates and their assumptions are distinct from modification time`() {
        val known = hit("IMG_1.jpg", "/storage/emulated/0/IMG_1.jpg", null)
            .copy(capturedAtMs = 1_700_100_000_000L, capturedTimeSource = "EXIF; assumed zone America/New_York")
        val unknown = hit("IMG_2.jpg", "/storage/emulated/0/IMG_2.jpg", null)
        val out = SearchRendering.render("dog", listOf(known, unknown), built)
        assertTrue(out, out.contains("captured: ${SearchRendering.formatCaptureDate(known.capturedAtMs!!)}"))
        assertTrue(out, out.contains("EXIF; assumed zone America/New_York"))
        assertTrue(out, out.contains("captured: unknown"))
        assertTrue(out, out.contains("modified ${SearchRendering.formatDate(known.modifiedMs)}"))
    }
}
