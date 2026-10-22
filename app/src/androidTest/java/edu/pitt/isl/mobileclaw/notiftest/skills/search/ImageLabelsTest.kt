package edu.pitt.isl.mobileclaw.notiftest.skills.search

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
 * The real classifier on real pictures: the bundled ML Kit model, on the device
 * under test, recognises the two fixture photos for what they are, and the whole chain —
 * walk, classify, index, search — turns "cat" into the cat's path. Everything else about
 * labelling is covered with a fake in `FileIndexTest`; this is the one place the model
 * itself is on trial, and the one that would fail on an ABI the bundled model does not
 * ship for.
 *
 * The fixtures are in `androidTest/assets/search-fixtures/` with their provenance in the
 * README there; they are copied out to the app's external files directory because the
 * indexer walks a directory, not an asset manager.
 */
@RunWith(AndroidJUnit4::class)
class ImageLabelsTest {

    private lateinit var context: Context
    private lateinit var fixture: File
    private lateinit var index: FileIndex

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fixture = File(context.getExternalFilesDir(null), "label-fixtures").apply { deleteRecursively(); mkdirs() }
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        for (name in listOf("cat.jpg", "dog.jpg")) {
            // Camera-style names on purpose: nothing in the path may give the answer away.
            val target = if (name == "cat.jpg") "IMG_20260901_101500.jpg" else "IMG_20260901_101730.jpg"
            assets.open("search-fixtures/$name").use { input -> File(fixture, target).outputStream().use { input.copyTo(it) } }
        }
        File(fixture, "not_really.jpg").writeText("this is a text file wearing a jpg extension")
        index = FileIndex(context, databaseName = "mobileclaw-files-labeltest")
    }

    @After
    fun tearDown() {
        runBlocking { index.clear() }
        fixture.deleteRecursively()
    }

    @Test
    fun theBundledModelSeesACatAndADog() {
        val analyser = OnDevicePhotoAnalyser(ImageLabels(), clip = null)
        try {
            val cat = runBlocking { analyser.analyse(File(fixture, "IMG_20260901_101500.jpg")) }?.labels
            val dog = runBlocking { analyser.analyse(File(fixture, "IMG_20260901_101730.jpg")) }?.labels
            assertNotNull(cat); assertNotNull(dog)
            assertTrue("cat labels: $cat", cat!!.any { it.name == "cat" })
            assertTrue("dog labels: $dog", dog!!.any { it.name == "dog" })
            assertTrue(cat.all { it.confidence >= IndexPolicy.LABEL_MIN_CONFIDENCE })
            assertNull("a text file in a jpg's clothing is not an image", runBlocking { analyser.analyse(File(fixture, "not_really.jpg")) })
        } finally {
            analyser.close()
        }
    }

    @Test
    fun catFindsTheCatAndNotTheDogEndToEnd() {
        val outcome = runBlocking { FileIndexer(context, index, fixture, PhotoContent.Analyse(OnDevicePhotoAnalyser(ImageLabels(), null))).rebuild() }
        assertNull(outcome.error)
        assertEquals(3, outcome.filesIndexed)
        assertEquals("both photos and the impostor count as analysed", 3, outcome.imagesAnalysed)
        assertEquals(0, outcome.imagesPending)

        val cats = runBlocking { index.search("cat", IndexPolicy.Kind.IMAGE, 5) }
        assertEquals(listOf("IMG_20260901_101500.jpg"), cats.map { it.name })
        val dogs = runBlocking { index.search("dog", IndexPolicy.Kind.IMAGE, 5) }
        assertEquals(listOf("IMG_20260901_101730.jpg"), dogs.map { it.name })
    }
}
