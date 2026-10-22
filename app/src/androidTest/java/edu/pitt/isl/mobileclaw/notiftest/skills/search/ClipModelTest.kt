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
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * MobileCLIP on the device under test: the shipped export, ONNX Runtime's native
 * library for this ABI, and the Kotlin preprocessing, held to the Python reference and
 * to each other.
 *
 * Three things only this can show. That the Kotlin decode → scale → crop → tensor path
 * reproduces the reference pipeline's embedding (cosine > 0.99 against vectors computed
 * with PIL + onnxruntime on the same JPEGs, `search-fixtures/<name>.clip.txt`). That text and
 * image land in the same space with the calibrated margin — the cat photo scores the cat
 * caption above [IndexPolicy.SIMILARITY_MIN] and the dog caption below it. And that the
 * whole chain — walk, embed, index, `semanticSearch` — turns "a photo of a cat" into the
 * cat's path and nothing else. Skips, not fails, on a checkout without the weights.
 */
@RunWith(AndroidJUnit4::class)
class ClipModelTest {

    private lateinit var context: Context
    private lateinit var fixture: File
    private lateinit var index: FileIndex
    private lateinit var model: ClipModel

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        assumeTrue("CLIP weights not in this build (tools/fetch_clip_model.sh)", ClipModel.isAvailable(context))
        model = ClipModel.get(context) ?: throw AssertionError("weights present but the model did not load — see MobileClaw-FS")
        fixture = File(context.getExternalFilesDir(null), "clip-fixtures").apply { deleteRecursively(); mkdirs() }
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        for ((asset, target) in listOf("cat.jpg" to "IMG_20260901_101500.jpg", "dog.jpg" to "IMG_20260901_101730.jpg")) {
            assets.open("search-fixtures/$asset").use { input -> File(fixture, target).outputStream().use { input.copyTo(it) } }
        }
        index = FileIndex(context, databaseName = "mobileclaw-files-cliptest")
    }

    @After
    fun tearDown() {
        if (this::index.isInitialized) runBlocking { index.clear() }
        if (this::fixture.isInitialized) fixture.deleteRecursively()
    }

    private fun reference(name: String): FloatArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open("search-fixtures/$name.clip.txt")
            .bufferedReader().readText().trim().split(' ').map { it.toFloat() }.toFloatArray()

    private fun embed(file: String): FloatArray {
        val bitmap = ImageDecode.scaled(File(fixture, file)) ?: throw AssertionError("$file did not decode")
        try { return model.embedImage(bitmap) } finally { bitmap.recycle() }
    }

    @Test
    fun theKotlinPipelineReproducesTheReferenceEmbeddings() {
        val cat = embed("IMG_20260901_101500.jpg")
        val dog = embed("IMG_20260901_101730.jpg")
        assertEquals(ClipModel.DIMS, cat.size)
        val catRef = ClipModel.cosine(cat, reference("cat"))
        val dogRef = ClipModel.cosine(dog, reference("dog"))
        assertTrue("cat vs Python reference: $catRef", catRef > 0.99f)
        assertTrue("dog vs Python reference: $dogRef", dogRef > 0.99f)
        assertTrue("the two photos are not each other", ClipModel.cosine(cat, dog) < 0.9f)
    }

    @Test
    fun textAndImageShareASpaceWithTheCalibratedMargin() {
        val cat = embed("IMG_20260901_101500.jpg")
        val dog = embed("IMG_20260901_101730.jpg")
        val catText = model.embedText(IndexPolicy.similarityPrompt("cat"))
        val dogText = model.embedText(IndexPolicy.similarityPrompt("dog"))
        val cc = ClipModel.cosine(cat, catText); val cd = ClipModel.cosine(cat, dogText)
        val dd = ClipModel.cosine(dog, dogText); val dc = ClipModel.cosine(dog, catText)
        assertTrue("cat·cat $cc above the floor", cc >= IndexPolicy.SIMILARITY_MIN)
        assertTrue("dog·dog $dd above the floor", dd >= IndexPolicy.SIMILARITY_MIN)
        assertTrue("cat·dog $cd below the floor", cd < IndexPolicy.SIMILARITY_MIN)
        assertTrue("dog·cat $dc below the floor", dc < IndexPolicy.SIMILARITY_MIN)
    }

    @Test
    fun aPhotoOfACatFindsTheCatEndToEnd() {
        val analyser = OnDevicePhotoAnalyser(labels = null, clip = model)
        val outcome = runBlocking { FileIndexer(context, index, fixture, PhotoContent.Analyse(analyser)).rebuild() }
        assertNull(outcome.error)
        assertEquals(2, outcome.imagesAnalysed)

        val cats = runBlocking { index.searchSimilar(model.embedText("a photo of a cat"), IndexPolicy.SIMILARITY_MIN, 5) }
        assertEquals(listOf("IMG_20260901_101500.jpg"), cats.map { it.name })
        assertNotNull(cats[0].similarity)
        val dogs = runBlocking { index.searchSimilar(model.embedText("a photo of a dog"), IndexPolicy.SIMILARITY_MIN, 5) }
        assertEquals(listOf("IMG_20260901_101730.jpg"), dogs.map { it.name })
        assertTrue("nothing on the card looks like a bridge", runBlocking { index.searchSimilar(model.embedText("a photo of a bridge"), IndexPolicy.SIMILARITY_MIN, 5) }.isEmpty())
    }
}
