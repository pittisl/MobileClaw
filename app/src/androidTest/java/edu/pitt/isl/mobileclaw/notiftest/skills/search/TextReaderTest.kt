package edu.pitt.isl.mobileclaw.notiftest.skills.search

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Debug
import android.util.Log
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
 * Exercises the bundled ML Kit recognizer on generated boarding passes rendered
 * as a screenshot, PDF and a small area of a 12 MP image. The indexing and search
 * chain must make the flight number findable. Other OCR integration cases use a
 * fake analyzer in FileIndexTest.
 *
 * Fixtures are drawn with the platform font and contain no real travel records.
 * The photo uses 24 px type in a 4000 px frame to check [IndexPolicy.OCR_TARGET_PX]:
 * small text must survive decoding at the OCR target size. A larger font would
 * not distinguish the OCR decode target from the smaller classifier input.
 */
@RunWith(AndroidJUnit4::class)
class TextReaderTest {

    private lateinit var context: Context
    private lateinit var fixture: File
    private lateinit var index: FileIndex

    private val lines = listOf(
        "BOARDING PASS",
        "Flight UA1234   PIT to AUS",
        "Departs 07:15   Gate B12   Seat 14C",
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fixture = File(context.getExternalFilesDir(null), "ocr-fixtures").apply { deleteRecursively(); mkdirs() }
        index = FileIndex(context, databaseName = "mobileclaw-files-ocrtest")
    }

    @After
    fun tearDown() {
        runBlocking { index.clear() }
        fixture.deleteRecursively()
    }

    private fun paint(px: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textSize = px; typeface = Typeface.MONOSPACE
    }

    /** A phone-screenshot-sized PNG with the pass on it at screen-text size. */
    private fun screenshot(name: String): File {
        val bmp = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val c = Canvas(bmp)
        lines.forEachIndexed { i, l -> c.drawText(l, 60f, 600f + i * 90f, paint(52f)) }
        return File(fixture, name).also { f -> f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }; bmp.recycle() }
    }

    /** A Letter-size PDF with the pass in 12 pt type, as an airline's e-ticket would be. */
    private fun pdf(name: String): File {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(612, 792, 1).create())
        lines.forEachIndexed { i, l -> page.canvas.drawText(l, 72f, 120f + i * 20f, paint(12f)) }
        doc.finishPage(page)
        return File(fixture, name).also { f -> f.outputStream().use { doc.writeTo(it) }; doc.close() }
    }

    /** A 12 MP camera frame, mostly grey, with the pass in 24 px type in one corner. */
    private fun photo(name: String): File {
        val bmp = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(120, 130, 120)) }
        val c = Canvas(bmp)
        c.drawRect(800f, 900f, 1900f, 1200f, Paint().apply { color = Color.WHITE })
        lines.forEachIndexed { i, l -> c.drawText(l, 840f, 980f + i * 42f, paint(24f)) }
        return File(fixture, name).also { f -> f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bmp.recycle() }
    }

    private fun analyser() = OnDevicePhotoAnalyser(ImageLabels(), ClipModel.get(context), TextReader())

    @Test
    fun aScreenshotIsRead() {
        val f = analyser().use { a -> runBlocking { a.analyse(screenshot("Screenshot_20261001_081500.png")) } }
        assertNotNull(f)
        assertTrue(f!!.read)
        assertTrue(f.text, f.text!!.contains("UA1234"))
        assertTrue(f.text, f.text!!.contains("07:15"))
    }

    @Test
    fun aPdfPageIsRenderedOnWhiteAndRead() {
        val f = analyser().use { a -> runBlocking { a.analyse(pdf("e-ticket.pdf")) } }
        assertNotNull(f)
        assertTrue(f!!.read)
        assertTrue(f.text, f.text!!.contains("UA1234"))
        assertTrue(f.text, f.text!!.contains("Gate B12"))
        assertTrue("a PDF gets no labels", f.labels.isEmpty())
        assertNull("nor a vector", f.embedding)
    }

    @Test
    fun theSmallPrintInATwelveMegapixelPhotoIsRead() {
        val file = photo("IMG_20261001_081500.jpg")
        val before = Debug.getNativeHeapAllocatedSize()
        val started = System.currentTimeMillis()
        val f = analyser().use { a -> runBlocking { a.analyse(file) } }
        Log.i(FileIndexer.TAG, "12 MP photo: ${System.currentTimeMillis() - started} ms, native heap " +
            "${before / 1_048_576} → ${Debug.getNativeHeapAllocatedSize() / 1_048_576} MB, text=${f?.text}")
        assertNotNull(f)
        assertTrue(f!!.text, f.text?.contains("UA1234") == true)
    }

    @Test
    fun aPdfThatIsNotOneIsReadWithNothing() {
        File(fixture, "broken.pdf").writeText("this is not a PDF at all")
        val f = analyser().use { a -> runBlocking { a.analyse(File(fixture, "broken.pdf")) } }
        assertNull("unreadable is null, which the indexer records as read-with-nothing", f)
    }

    @Test
    fun walkToSearchFindsThePassByWhatIsPrintedOnIt() {
        screenshot("Screenshot_20261001_081500.png")
        pdf("e-ticket.pdf")
        File(fixture, "broken.pdf").writeText("this is not a PDF at all")
        val outcome = runBlocking { FileIndexer(context, index, fixture, PhotoContent.Analyse(analyser())).rebuild() }
        assertNull(outcome.error)
        assertEquals(3, outcome.imagesAnalysed)
        assertEquals(0, outcome.imagesPending)

        val hits = runBlocking { index.search("UA1234", null, 5) }
        assertEquals(setOf("Screenshot_20261001_081500.png", "e-ticket.pdf"), hits.map { it.name }.toSet())
        val rendered = SearchRendering.render("UA1234", hits, IndexState.Snapshot(1L, 3, 0, 0, 0, null))
        assertTrue(rendered, rendered.contains("text read from it by OCR"))
        assertTrue(rendered, rendered.contains("07:15"))
    }
}
