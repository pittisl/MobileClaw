package edu.pitt.isl.mobileclaw.notiftest.skills.search

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The index end to end: a fixture folder goes in, the right file comes out,
 * and a root the file-system policy denies produces no index at all.
 *
 * Instrumented because AppSearch's `LocalStorage` is a native library with a real
 * on-disk store; there is no JVM stand-in. The fixture lives under the app's own
 * external files directory, which needs no permission and sits under shared storage —
 * so the policy allows it — and the index uses its own database name so a test never
 * touches the one the app builds.
 */
@RunWith(AndroidJUnit4::class)
class FileIndexTest {

    private lateinit var context: Context
    private lateinit var fixture: File
    private lateinit var index: FileIndex

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fixture = File(context.getExternalFilesDir(null), "search-fixtures").apply {
            deleteRecursively(); mkdirs()
        }
        File(fixture, "Travel").mkdirs()
        File(fixture, "Travel/hotel_receipt-austin.txt").writeText(
            "Hotel receipt. Austin, TX. Two nights, room 412. Total 389.20 USD. Card ending 4242."
        )
        File(fixture, "budget-2026.md").writeText("# Q3 budget review\n\nQuarterly budget for the lab: equipment, travel, phones.")
        File(fixture, "IMG_20260812_whiteboard.jpg").writeBytes(ByteArray(64) { 0x7f })
        File(fixture, ".nomedia").writeText("")
        File(fixture, "Android").mkdirs()
        File(fixture, "Android/private.txt").writeText("secret budget notes that must not be indexed")
        index = FileIndex(context, databaseName = "mobileclaw-files-test")
    }

    @After
    fun tearDown() {
        runBlocking { index.clear() }
        fixture.deleteRecursively()
    }

    /**
     * Stands in for the classifier: answers from a table by file name and counts calls, so
     * the tests can say not only what the index holds but how many times the expensive
     * step ran to get there. Nothing here decodes pixels — `ImageLabelsTest` covers the
     * real model.
     */
    private class FakeLabels(
        private val table: Map<String, List<IndexPolicy.Label>>,
        private val vectors: Map<String, FloatArray> = emptyMap(),
        override val embeds: Boolean = false,
        /** What the fake "reads" off a file, for the OCR cases; [reads] says whether it reads at all. */
        private val texts: Map<String, String> = emptyMap(),
        override val reads: Boolean = false,
    ) : PhotoAnalyser {
        val asked = mutableListOf<String>()
        var closed = false
        override suspend fun analyse(file: File): PhotoFeatures? {
            asked += file.name
            val text = if (reads) IndexPolicy.keepText(texts[file.name]) else null
            val labels = table[file.name] ?: if (text != null) emptyList() else return null
            return PhotoFeatures(labels, vectors[file.name], embedded = embeds, text = text, read = reads)
        }
        override fun close() { closed = true }
    }

    private val catLabels = listOf(IndexPolicy.Label("cat", 0.93f), IndexPolicy.Label("pet", 0.88f))

    /** A unit vector pointing along one axis, so two of them are orthogonal and a query along one is exact. */
    private fun axis(i: Int) = FloatArray(ClipModel.DIMS).also { it[i] = 1f }

    private fun rebuild(root: File = fixture, labels: PhotoContent = PhotoContent.Deferred) =
        runBlocking { FileIndexer(context, index, root, labels).rebuild() }

    private fun rebuild(labels: PhotoAnalyser) = rebuild(labels = PhotoContent.Analyse(labels))

    @Test
    fun aRebuildIndexesTheFixtureAndSkipsWhatPolicySays() {
        val outcome = rebuild()
        assertNull(outcome.error)
        // receipt, budget, image — not the dotfile, not the Android/ subtree.
        assertEquals(3, outcome.filesIndexed)
        assertEquals(2, outcome.withContent)
        assertEquals(3, runBlocking { index.count() })
    }

    @Test
    fun contentWordsFindTheFileAndComeBackAsASnippet() {
        rebuild()
        val hits = runBlocking { index.search("quarterly budget", null, 5) }
        assertEquals(1, hits.size)
        assertEquals("budget-2026.md", hits[0].name)
        assertTrue(hits[0].path.endsWith("/search-fixtures/budget-2026.md"))
        assertNotNull("a content match carries its passage", hits[0].snippet)
        assertTrue(hits[0].snippet!!.contains("Quarterly budget", ignoreCase = true))
    }

    @Test
    fun nameAndFolderWordsFindFilesWithNoIndexedBody() {
        rebuild()
        val byName = runBlocking { index.search("whiteboard", null, 5) }
        assertEquals(listOf("IMG_20260812_whiteboard.jpg"), byName.map { it.name })
        assertNull("an image has no body to quote", byName[0].snippet)

        // "receip" is a prefix; "Travel" is the folder, not the name.
        val byPrefix = runBlocking { index.search("receip", null, 5) }
        assertEquals(listOf("hotel_receipt-austin.txt"), byPrefix.map { it.name })
        val byFolder = runBlocking { index.search("travel", null, 5) }
        assertTrue(byFolder.any { it.name == "hotel_receipt-austin.txt" })
    }

    @Test
    fun theKindFilterHolds() {
        rebuild()
        val images = runBlocking { index.search("austin OR whiteboard OR budget", IndexPolicy.Kind.IMAGE, 5) }
        assertEquals(listOf("IMG_20260812_whiteboard.jpg"), images.map { it.name })
    }

    // ---- image labels ----

    @Test
    fun aLabelledPhotoIsFoundByWhatIsInIt() {
        File(fixture, "IMG_20260901_120000.jpg").writeBytes(ByteArray(64) { 0x10 })
        val fake = FakeLabels(mapOf("IMG_20260901_120000.jpg" to catLabels))
        val outcome = rebuild(fake)
        assertNull(outcome.error)
        // Both jpgs qualify; the whiteboard one gets an empty answer from the fake (a decode
        // failure in real life) and is still counted as labelled so it is not retried.
        assertEquals(2, outcome.imagesAnalysed)
        assertEquals(0, outcome.imagesPending)
        assertTrue(fake.closed)

        val hits = runBlocking { index.search("cat", IndexPolicy.Kind.IMAGE, 5) }
        assertEquals(listOf("IMG_20260901_120000.jpg"), hits.map { it.name })
        assertEquals(catLabels.map { it.name }, hits[0].labels!!.map { it.name })
        assertEquals(0.93f, hits[0].labels!![0].confidence, 0.001f)
        assertNull("a label is not a text passage", hits[0].snippet)
        // Nothing about the file's name or folder says "cat": the label field is the only route.
        assertTrue(runBlocking { index.search("cat", IndexPolicy.Kind.TEXT, 5) }.isEmpty())
    }

    @Test
    fun labelsAreCarriedAcrossRebuildsAndOnlyChangedPhotosAreClassifiedAgain() {
        val photo = File(fixture, "IMG_20260901_120000.jpg").apply { writeBytes(ByteArray(64) { 0x10 }) }
        rebuild(FakeLabels(mapOf(photo.name to catLabels)))

        val second = FakeLabels(mapOf(photo.name to listOf(IndexPolicy.Label("dog", 0.9f))))
        val outcome = rebuild(second)
        assertEquals("unchanged photos are not classified again", emptyList<String>(), second.asked)
        assertEquals(2, outcome.imagesAnalysed)
        assertEquals(listOf(photo.name), runBlocking { index.search("cat", null, 5) }.map { it.name })

        // Edit the photo: the old labels no longer describe it and must be replaced.
        photo.writeBytes(ByteArray(128) { 0x20 })
        photo.setLastModified(photo.lastModified() + 5_000)
        val third = FakeLabels(mapOf(photo.name to listOf(IndexPolicy.Label("dog", 0.9f))))
        rebuild(third)
        assertEquals(listOf(photo.name), third.asked)
        assertTrue(runBlocking { index.search("cat", null, 5) }.isEmpty())
        assertEquals(listOf(photo.name), runBlocking { index.search("dog", null, 5) }.map { it.name })
    }

    @Test
    fun aPhotoIsFoundBySimilarityToAVectorAndScored() {
        val cat = File(fixture, "IMG_20260901_120000.jpg").apply { writeBytes(ByteArray(64) { 0x10 }) }
        val other = File(fixture, "IMG_20260901_120100.jpg").apply { writeBytes(ByteArray(64) { 0x11 }) }
        val fake = FakeLabels(
            mapOf(cat.name to catLabels, other.name to emptyList()),
            vectors = mapOf(cat.name to axis(0), other.name to axis(1)),
            embeds = true,
        )
        assertNull(rebuild(fake).error)

        // A query along the cat's axis: the cat at cosine 1, the other photo at 0 and below the floor.
        val hits = runBlocking { index.searchSimilar(axis(0), 0.5f, 5) }
        assertEquals(listOf(cat.name), hits.map { it.name })
        assertEquals(1f, hits[0].similarity!!, 0.01f)
        assertEquals("labels ride along on a similarity hit", listOf("cat", "pet"), hits[0].labels!!.map { it.name })
        // Halfway between the two axes scores both at ~0.71, best first is a tie broken by the index; both present.
        val both = runBlocking { index.searchSimilar(ClipModel.normalise(floatArrayOf(1f, 1f) + FloatArray(ClipModel.DIMS - 2)), 0.5f, 5) }
        assertEquals(setOf(cat.name, other.name), both.map { it.name }.toSet())
        // The floor is a real cut, not a ranking hint.
        assertTrue(runBlocking { index.searchSimilar(axis(2), 0.5f, 5) }.isEmpty())
    }

    @Test
    fun aVectorlessRecordIsRedoneOnceAnAnalyserThatEmbedsArrives() {
        val photo = File(fixture, "IMG_20260901_120000.jpg").apply { writeBytes(ByteArray(64) { 0x10 }) }
        rebuild(FakeLabels(mapOf(photo.name to catLabels)))                       // labels only, as a build without weights
        val labelsOnlyAgain = FakeLabels(mapOf(photo.name to catLabels))
        rebuild(labelsOnlyAgain)
        assertEquals("complete for an analyser that cannot embed", emptyList<String>(), labelsOnlyAgain.asked)

        val withClip = FakeLabels(mapOf(photo.name to catLabels), vectors = mapOf(photo.name to axis(0)), embeds = true)
        rebuild(withClip)
        // Both jpgs are redone: the whiteboard too, whose record was labels-only as well.
        assertEquals("incomplete for one that can", setOf(photo.name, "IMG_20260812_whiteboard.jpg"), withClip.asked.toSet())
        assertEquals(1, runBlocking { index.searchSimilar(axis(0), 0.5f, 5) }.size)
        // And now it is complete for both kinds — the whiteboard included, which got no
        // vector (the fake, like a decoder, had nothing for it) and must still not be retried.
        val again = FakeLabels(mapOf(photo.name to catLabels), embeds = true)
        rebuild(again)
        assertEquals(emptyList<String>(), again.asked)
    }

    @Test
    fun theSwitchOffDropsLabelsAndOwesNone() {
        val photo = File(fixture, "IMG_20260901_120000.jpg").apply { writeBytes(ByteArray(64) { 0x10 }) }
        rebuild(FakeLabels(mapOf(photo.name to catLabels)))
        assertEquals(1, runBlocking { index.search("cat", null, 5) }.size)

        val outcome = rebuild(labels = PhotoContent.Off)
        assertNull(outcome.error)
        assertTrue(outcome.analysisOff)
        assertEquals("off owes nothing, or the worker would chase it forever", 0, outcome.imagesPending)
        assertEquals(0, outcome.imagesAnalysed)
        assertTrue("an off switch that still answers from old labels does nothing visible",
            runBlocking { index.search("cat", null, 5) }.isEmpty())
        assertEquals("the photo is still there by name", 1, runBlocking { index.search("120000", null, 5) }.size)

        // Back on: nothing was kept, so the classifier is asked again.
        val again = FakeLabels(mapOf(photo.name to catLabels))
        rebuild(again)
        assertTrue(photo.name in again.asked)
        assertEquals(1, runBlocking { index.search("cat", null, 5) }.size)
    }

    @Test
    fun withNoClassifierPhotosGoInByNameAndAreReportedPending() {
        File(fixture, "IMG_20260901_120000.jpg").writeBytes(ByteArray(64) { 0x10 })
        val outcome = rebuild(labels = PhotoContent.Deferred)
        assertNull(outcome.error)
        assertEquals(4, outcome.filesIndexed)
        assertEquals(0, outcome.imagesAnalysed)
        assertEquals(2, outcome.imagesPending)
        val byName = runBlocking { index.search("whiteboard", null, 5) }
        assertEquals(1, byName.size)
        assertNull("not labelled is null, not empty", byName[0].labels)

        // The next build with a classifier picks the pending ones up — and only them.
        val fake = FakeLabels(mapOf("IMG_20260901_120000.jpg" to catLabels))
        rebuild(fake)
        assertEquals(setOf("IMG_20260901_120000.jpg", "IMG_20260812_whiteboard.jpg"), fake.asked.toSet())
        assertEquals(1, runBlocking { index.search("cat", null, 5) }.size)
    }

    // ---- OCR ----

    private val passText = "BOARDING PASS Flight UA1234 From PIT To AUS Departs 07:15 Gate B12 Seat 14C"

    @Test
    fun textReadOffAPhotoOrAPdfIsSearchableAndComesBackWhole() {
        val shot = File(fixture, "Screenshot_20261001_081500.png").apply { writeBytes(ByteArray(64) { 0x30 }) }
        val pdf = File(fixture, "e-ticket.pdf").apply { writeBytes(ByteArray(64) { 0x31 }) }
        File(fixture, "minutes.docx").writeBytes(ByteArray(64) { 0x32 })
        val fake = FakeLabels(emptyMap(), texts = mapOf(shot.name to passText, pdf.name to "Hotel Ella Austin confirmation LKVQ7X"), reads = true)
        val outcome = rebuild(fake)
        assertNull(outcome.error)
        assertTrue("a PDF is handed to the analyser", pdf.name in fake.asked)
        assertTrue("other documents are not", "minutes.docx" !in fake.asked)

        val byFlight = runBlocking { index.search("UA1234", null, 5) }
        assertEquals(listOf(shot.name), byFlight.map { it.name })
        // The whole reading rides on the hit: it is all the model will learn of a screenshot.
        assertEquals(passText, byFlight[0].ocrText)
        assertNotNull("the OCR match carries its passage", byFlight[0].ocrSnippet)
        assertNull("an OCR match is not a content match", byFlight[0].snippet)

        assertEquals(listOf(pdf.name), runBlocking { index.search("LKVQ7X", null, 5) }.map { it.name })
        // A file OCR found nothing on has no text, not an empty one.
        assertNull(runBlocking { index.search("whiteboard", null, 5) }.single().ocrText)
    }

    @Test
    fun aRecordMadeBeforeOcrIsReadOnceAndThenCarried() {
        val shot = File(fixture, "Screenshot_20261001_081500.png").apply { writeBytes(ByteArray(64) { 0x30 }) }
        rebuild(FakeLabels(mapOf(shot.name to emptyList())))                      // a build from before OCR

        val reader = FakeLabels(mapOf(shot.name to emptyList()), texts = mapOf(shot.name to passText), reads = true)
        rebuild(reader)
        assertEquals("both images owe a reading", setOf(shot.name, "IMG_20260812_whiteboard.jpg"), reader.asked.toSet())
        assertEquals(1, runBlocking { index.search("UA1234", null, 5) }.size)

        val again = FakeLabels(emptyMap(), reads = true)
        rebuild(again)
        assertEquals("read once, carried after — the whiteboard too, which had no text", emptyList<String>(), again.asked)
        assertEquals("the text is carried, not just the flag", passText, runBlocking { index.search("UA1234", null, 5) }.single().ocrText)
    }

    // ---- incremental add (a photo just taken) ----

    @Test
    fun addPutsANewFileBesideTheIndexWithoutDroppingIt() {
        rebuild()
        val shot = File(fixture, "DCIM/Camera/IMG_20261001_081500.jpg").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(64) { 0x40 }) }
        val hidden = File(fixture, "DCIM/.pending/IMG_x.jpg").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(64) { 0x41 }) }
        val private = File(fixture, "Android/new.txt").apply { writeText("budget notes that must not be indexed") }
        val fake = FakeLabels(emptyMap(), texts = mapOf(shot.name to passText), reads = true)

        val outcome = runBlocking { FileIndexer(context, index, fixture, PhotoContent.Analyse(fake)).add(listOf(shot, hidden, private)) }
        assertNull(outcome.error)
        assertEquals("only the photo is admitted", 1, outcome.filesIndexed)
        assertEquals("the walk's refusals hold here too", 2, outcome.skippedDenied)
        assertEquals("only the new file is analysed", listOf(shot.name), fake.asked)
        assertTrue(fake.closed)

        assertEquals(listOf(shot.name), runBlocking { index.search("UA1234", null, 5) }.map { it.name })
        assertEquals("nothing was cleared", 4, runBlocking { index.count() })
        assertEquals(1, runBlocking { index.search("quarterly budget", null, 5) }.size)
    }

    @Test
    fun aRebuildAfterAnAddAgreesWithIt() {
        rebuild()
        val shot = File(fixture, "DCIM/Camera/IMG_20261001_081500.jpg").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(64) { 0x40 }) }
        runBlocking { FileIndexer(context, index, fixture, PhotoContent.Analyse(FakeLabels(emptyMap(), texts = mapOf(shot.name to passText), reads = true))).add(listOf(shot)) }
        // The walk finds the same file and carries the add's reading rather than redoing it.
        val after = FakeLabels(emptyMap(), reads = true)
        rebuild(after)
        assertTrue("the added photo was not read twice", shot.name !in after.asked)
        assertEquals(listOf(shot.name), runBlocking { index.search("UA1234", null, 5) }.map { it.name })
    }

    @Test
    fun aRootTheFileSystemPolicyDeniesProducesNoIndex() {
        // App-private internal storage is off limits to file_system; the index must not be
        // the way around that. Nothing is walked, nothing is written, and the outcome says why.
        val outcome = rebuild(root = context.filesDir)
        assertNotNull(outcome.error)
        assertTrue(outcome.error!!.contains("refused"))
        assertEquals(0, outcome.filesIndexed)
    }

    @Test
    fun aRebuildForgetsFilesThatWereDeleted() {
        rebuild()
        File(fixture, "budget-2026.md").delete()
        rebuild()
        assertTrue(runBlocking { index.search("quarterly", null, 5) }.isEmpty())
        assertEquals(2, runBlocking { index.count() })
    }
}
