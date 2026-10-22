package edu.pitt.isl.mobileclaw.notiftest.skills.search

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Acceptance of the actual booth files, not freshly drawn test substitutes.
 * The test APK packages tools/demo_preload/generated and copies it into app-owned
 * external test storage. This does not require broad shared-storage access.
 * These cases make no model requests, send no messages and use a separate index;
 * the shared files and the application's real index/history are never cleared.
 */
@RunWith(AndroidJUnit4::class)
class DemoCorpusTest {
    private lateinit var context: Context
    private lateinit var index: FileIndex
    private lateinit var fixture: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fixture = File(context.getExternalFilesDir(null), "demo-corpus-acceptance").apply { mkdirs() }
        File(fixture, "pdfs").mkdirs()
        File(fixture, "images").mkdirs()
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        for (name in listOf("hotel-confirmation.pdf", "course-handout.pdf", "meeting-note.pdf",
            "boarding-pass.png", "restaurant-card.png")) {
            val destination = if (name.endsWith(".pdf")) document(name) else image(name)
            assets.open(name).use { source -> destination.outputStream().use { source.copyTo(it) } }
        }
        index = FileIndex(context, databaseName = "mobileclaw-demo-corpus-acceptance")
        runBlocking { index.clear() }
    }

    @After
    fun tearDown() {
        if (::index.isInitialized) runBlocking { index.clear() }
        if (::fixture.isInitialized) fixture.deleteRecursively()
    }

    private fun document(name: String) = File(fixture, "pdfs/$name")
    private fun image(name: String) = File(fixture, "images/$name")

    private fun read(file: File): String {
        assertTrue("Preload missing: ${file.path}", file.isFile)
        val started = SystemClock.elapsedRealtime()
        val features = OnDevicePhotoAnalyser(null, null, TextReader()).use { analyser ->
            runBlocking { analyser.analyse(file) }
        }
        Log.i(TAG, "${file.name}: OCR ${SystemClock.elapsedRealtime() - started} ms")
        assertNotNull("No features for ${file.name}", features)
        assertTrue("OCR not attempted for ${file.name}", features!!.read)
        assertNotNull("No OCR text for ${file.name}", features.text)
        return features.text!!
    }

    private fun contains(text: String, expected: String) {
        assertTrue("Expected '$expected' in OCR: $text", text.contains(expected, ignoreCase = true))
    }

    @Test
    fun shippedPdfsExposeBookingCourseAndScannedMeetingFacts() {
        val hotel = read(document("hotel-confirmation.pdf"))
        contains(hotel, "Cedar House Austin")
        contains(hotel, "LKVQ7X")
        contains(hotel, "CedarDemo") // Page two: proves that the first-page-only path is insufficient.
        contains(hotel, "1,236.80")
        val course = read(document("course-handout.pdf"))
        contains(course, "November 2")
        contains(course, "17:00")
        contains(course, "Benedum 204")
        val meeting = read(document("meeting-note.pdf")) // Image-only PDF, no embedded text to fall back to.
        contains(meeting, "15:45")
        contains(meeting, "16:15")
    }

    @Test
    fun shippedImagesExposeFlightAndRestaurantFacts() {
        val boarding = read(image("boarding-pass.png"))
        contains(boarding, "UA1234")
        contains(boarding, "09:40")
        contains(boarding, "B18")
        contains(boarding, "12A")
        val restaurant = read(image("restaurant-card.png"))
        contains(restaurant, "Juniper Table")
        contains(restaurant, "JNPR8K")
        contains(restaurant, "18:30")
    }

    @Test
    fun fileContentsBecomeSearchableAndReachTheModelRendering() = runBlocking<Unit> {
        val files = listOf(document("hotel-confirmation.pdf"), document("course-handout.pdf"),
            document("meeting-note.pdf"), image("boarding-pass.png"), image("restaurant-card.png"))
        files.forEach { assertTrue("Preload missing: ${it.path}", it.isFile) }
        val started = SystemClock.elapsedRealtime()
        val outcome = FileIndexer(context, index, fixture,
            PhotoContent.Analyse(OnDevicePhotoAnalyser(null, null, TextReader()))).add(files)
        assertEquals(outcome.error, null, outcome.error)
        assertEquals(5, outcome.filesIndexed)
        assertEquals(5, outcome.imagesAnalysed)
        assertEquals(0, outcome.imagesPending)
        val state = IndexState.Snapshot(System.currentTimeMillis(), 5, 0, 0, outcome.durationMs, null,
            imagesAnalysed = 5)
        val cases = listOf(
            Triple("LKVQ7X", "hotel-confirmation.pdf", "Cedar House Austin"),
            Triple("CedarDemo", "hotel-confirmation.pdf", "CedarDemo"),
            Triple("UA1234", "boarding-pass.png", "09:40"),
            Triple("JNPR8K", "restaurant-card.png", "18:30"),
            Triple("Benedum", "course-handout.pdf", "Benedum 204"),
        )
        for ((query, name, fact) in cases) {
            val hits = index.search(query, null, 8)
            assertTrue("No content hit for $query: $hits", hits.any { it.name == name })
            val rendered = SearchRendering.render(query, hits, state)
            contains(rendered, "text read from it by OCR")
            contains(rendered, fact)
            Log.i(TAG, "$query -> $name; expected fact reaches search rendering")
        }
        assertTrue(index.search("XYZZYUNSEENFACT", null, 8).isEmpty())
        Log.i(TAG, "5-file OCR/index/search acceptance: ${SystemClock.elapsedRealtime() - started} ms")
    }

    private companion object {
        const val TAG = "MobileClaw-DEMO-OCR"
    }
}
