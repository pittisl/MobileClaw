package edu.pitt.isl.mobileclaw.notiftest.skills.search

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Measures MobileCLIP initialization, image embedding and text embedding latency on
 * the selected device. Correctness is covered separately by ClipModelTest; these
 * measurements characterize the optional similarity route's device cost.
 */
@RunWith(AndroidJUnit4::class)
class ClipLatencyTest {

    private lateinit var context: Context
    private lateinit var fixture: File
    private lateinit var index: FileIndex

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        assumeTrue("CLIP weights not in this build", ClipModel.isAvailable(context))
        fixture = File(context.getExternalFilesDir(null), "clip-latency").apply { deleteRecursively(); mkdirs() }
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        for ((asset, target) in listOf("cat.jpg" to "IMG_20260901_101500.jpg", "dog.jpg" to "IMG_20260901_101730.jpg")) {
            assets.open("search-fixtures/$asset").use { input -> File(fixture, target).outputStream().use { input.copyTo(it) } }
        }
        index = FileIndex(context, databaseName = "mobileclaw-files-latency")
    }

    @After
    fun tearDown() {
        if (this::index.isInitialized) runBlocking { index.clear() }
        if (this::fixture.isInitialized) fixture.deleteRecursively()
    }

    private inline fun ms(block: () -> Unit): Long {
        val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1_000_000
    }

    private fun median(runs: Int, block: () -> Unit): Long = (1..runs).map { ms(block) }.sorted()[runs / 2]

    @Test
    fun measure() {
        // Cold load, asset copy included — a fresh instrumentation process has no shared model yet.
        File(context.filesDir, "clip").deleteRecursively()
        var model: ClipModel? = null
        val load = ms { model = ClipModel.get(context) }
        assertNotNull("model did not load — see MobileClaw-FS", model)
        val clip = model!!

        val catFile = File(fixture, "IMG_20260901_101500.jpg")
        val bitmap = ImageDecode.scaled(catFile)!!
        repeat(2) { clip.embedImage(bitmap) }
        val image = median(10) { clip.embedImage(bitmap) }
        val query = IndexPolicy.similarityPrompt("golden retriever")
        repeat(2) { clip.embedText(query) }
        val text = median(10) { clip.embedText(query) }

        val analyser = OnDevicePhotoAnalyser(ImageLabels(), clip)
        runBlocking { analyser.analyse(catFile) }
        val photo = median(5) { runBlocking { analyser.analyse(catFile) } }
        analyser.close()

        runBlocking { FileIndexer(context, index, fixture, PhotoContent.Analyse(OnDevicePhotoAnalyser(null, clip))).rebuild() }
        val vector = clip.embedText(query)
        runBlocking { index.searchSimilar(vector, IndexPolicy.SIMILARITY_MIN, 5) }
        val search = median(5) { runBlocking { index.searchSimilar(vector, IndexPolicy.SIMILARITY_MIN, 5) } }
        bitmap.recycle()

        val line = "latency: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.SUPPORTED_ABIS.first()}, API ${Build.VERSION.SDK_INT}) " +
            "load=${load}ms image=${image}ms text=${text}ms photo(decode+labels+clip)=${photo}ms search=${search}ms " +
            "bitmap=${bitmap.width}x${bitmap.height}"
        Log.i(FileIndexer.TAG, line)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("latency", line) })
    }
}
