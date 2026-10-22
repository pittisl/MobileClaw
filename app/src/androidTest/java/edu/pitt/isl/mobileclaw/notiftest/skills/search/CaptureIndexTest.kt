package edu.pitt.isl.mobileclaw.notiftest.skills.search

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId

/** Native AppSearch and real JPEG metadata: neither has a useful JVM framework substitute. */
@RunWith(AndroidJUnit4::class)
class CaptureIndexTest {
    private lateinit var context: Context
    private lateinit var fixture: File
    private lateinit var index: FileIndex
    private val capturedAt = Instant.parse("2026-10-14T13:15:00Z").toEpochMilli()
    private val filter = CaptureDate.filter(on = "2026-10-14", zone = ZoneId.of("America/New_York"))!!

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fixture = File(context.getExternalFilesDir(null), "capture-index-fixtures").apply { deleteRecursively(); mkdirs() }
        index = FileIndex(context, "mobileclaw-capture-index-test")
        runBlocking { index.clear() }
    }

    @After fun tearDown() {
        runBlocking { index.clear() }
        fixture.deleteRecursively()
    }

    @Test fun exifCaptureDateSurvivesIncrementalAddAndRebuildIndependentlyOfMtime() = runBlocking {
        val photo = File(fixture, "IMG_001.jpg")
        InstrumentationRegistry.getInstrumentation().context.assets.open("search-fixtures/dog.jpg").use { input ->
            photo.outputStream().use { input.copyTo(it) }
        }
        ExifInterface(photo.path).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:10:14 09:15:00")
            setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "-04:00")
            saveAttributes()
        }
        photo.setLastModified(Instant.parse("2026-10-15T16:00:00Z").toEpochMilli())
        val indexer = FileIndexer(context, index, fixture, PhotoContent.Off)
        assertNull(indexer.add(listOf(photo)).error)
        val added = index.search("IMG", IndexPolicy.Kind.IMAGE, 1, filter).single()
        assertEquals(capturedAt, added.capturedAtMs)
        assertEquals("EXIF -04:00", added.capturedTimeSource)
        assertNotEquals(added.modifiedMs, added.capturedAtMs)
        assertNull(FileIndexer(context, index, fixture, PhotoContent.Off).rebuild().error)
        val rebuilt = index.search("IMG", IndexPolicy.Kind.IMAGE, 1, filter).single()
        assertEquals(added.capturedAtMs, rebuilt.capturedAtMs)
        assertTrue(index.search("IMG", IndexPolicy.Kind.IMAGE, 1,
            CaptureDate.filter(on = "2026-10-15", zone = ZoneId.of("America/New_York"))).isEmpty())
    }

    @Test fun explicitExifOffsetCrossesMidnightInTheDeviceDateFilter() = runBlocking {
        val photo = File(fixture, "dog_offset.jpg")
        InstrumentationRegistry.getInstrumentation().context.assets.open("search-fixtures/dog.jpg").use { input ->
            photo.outputStream().use { input.copyTo(it) }
        }
        // Use the same supported EXIF implementation on every device, including API 26
        // where the platform reader silently discards OffsetTimeOriginal.
        ExifInterface(photo.path).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:10:14 23:30:00")
            setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "-04:00")
            saveAttributes()
        }
        val utc = ZoneId.of("UTC")
        val expected = Instant.parse("2026-10-15T03:30:00Z").toEpochMilli()
        val capture = PhotoCaptureReader(context).read(photo, utc)
        assertNotNull(capture)
        assertEquals(expected, capture!!.atMs)
        assertEquals("EXIF -04:00", capture.source)
        assertNull(FileIndexer(context, index, fixture, PhotoContent.Off).add(listOf(photo)).error)
        val nextDay = index.search("dog", IndexPolicy.Kind.IMAGE, 1,
            CaptureDate.filter(on = "2026-10-15", zone = utc)).single()
        assertEquals(expected, nextDay.capturedAtMs)
        assertTrue(index.search("dog", IndexPolicy.Kind.IMAGE, 1,
            CaptureDate.filter(on = "2026-10-14", zone = utc)).isEmpty())
    }

    @Test fun unknownCaptureDateIsNotInventedFromFileModificationTime() = runBlocking {
        val unknown = File(fixture, "dog_unknown.jpg").apply {
            writeBytes(ByteArray(64) { 0x7f }); setLastModified(capturedAt)
        }
        FileIndexer(context, index, fixture, PhotoContent.Off).add(listOf(unknown))
        assertNull(index.search("dog", IndexPolicy.Kind.IMAGE, 1).single().capturedAtMs)
        assertTrue(index.search("dog", IndexPolicy.Kind.IMAGE, 1, filter).isEmpty())
    }

    @Test fun mediaStoreDateTakenIsTheFallbackForAnImageWithoutExif() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "capture-index-fallback.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MobileClawCaptureIndexTest")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        })!!
        try {
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            val withoutExif = try {
                ByteArrayOutputStream().also { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }.toByteArray()
            } finally { bitmap.recycle() }
            // DATE_TAKEN is an extracted, read-only column. Setting it in the publish
            // update was a broken fixture: publishing forces a scan that overwrites it.
            // Seed the real provider from real EXIF first, and verify that precondition.
            val seed = File(fixture, "fallback-seed.jpg").apply { writeBytes(withoutExif) }
            ExifInterface(seed.path).apply {
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:10:14 09:15:00")
                setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "-04:00")
                saveAttributes()
            }
            resolver.openOutputStream(uri)!!.use { output -> seed.inputStream().use { it.copyTo(output) } }
            assertEquals(1, resolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null))
            @Suppress("DEPRECATION")
            val path = resolver.query(uri,
                arrayOf(MediaStore.Images.Media.DATA, MediaStore.Images.Media.DATE_TAKEN), null, null, null)!!.use {
                assertTrue("Published fixture row is missing: $uri", it.moveToFirst())
                assertFalse("Scanner did not extract fixture DATE_TAKEN for $uri", it.isNull(1))
                assertEquals("Scanner extracted the wrong fixture date for $uri", capturedAt, it.getLong(1))
                it.getString(0)
            }
            // Model an image whose EXIF is now unavailable while MediaStore still
            // retains the capture date. MediaProvider refreshes metadata on writer
            // close; keep this owned writer open to make that interval deterministic.
            resolver.openOutputStream(uri, "rwt")!!.use { writer ->
                writer.write(withoutExif)
                writer.flush()
                assertNull("Fallback fixture unexpectedly still has EXIF capture time",
                    ExifInterface(path).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
                val file = File(path)
                @Suppress("DEPRECATION")
                val providerDate = resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media.DATE_TAKEN), "${MediaStore.Images.Media.DATA} = ?",
                    arrayOf(file.canonicalPath), null)!!.use {
                    assertTrue("No provider row for canonical path ${file.canonicalPath} (published path $path)", it.moveToFirst())
                    if (it.isNull(0)) null else it.getLong(0)
                }
                assertEquals("Fixture lost its cached MediaStore date before writer close: $path", capturedAt, providerDate)
                val capture = PhotoCaptureReader(context).read(file)
                assertNotNull("Reader lost a proven MediaStore date ($providerDate) for ${file.canonicalPath}", capture)
                assertEquals(capturedAt, capture!!.atMs)
                assertEquals("MediaStore DATE_TAKEN", capture.source)
            }
        } finally { resolver.delete(uri, null, null) }
    }

    private fun vector(score: Float) = FloatArray(ClipModel.DIMS).also {
        it[0] = score; it[1] = kotlin.math.sqrt(1f - score * score)
    }

    private fun entry(id: String, date: Long?, score: Float = 1f, repeatedMatch: Boolean = false) = FileIndex.Entry(
        canonicalPath = File(fixture, "$id.jpg").path,
        name = if (repeatedMatch) "dog.jpg" else "IMG.jpg",
        kind = IndexPolicy.Kind.IMAGE,
        sizeBytes = 100,
        modifiedMs = capturedAt,
        pathTokens = if (repeatedMatch) "dog" else "Pictures",
        content = if (repeatedMatch) "dog" else null,
        features = PhotoFeatures(listOf(IndexPolicy.Label("dog", 0.9f)), vector(score), embedded = true),
        capturedAtMs = date,
        capturedTimeSource = date?.let { "EXIF -04:00" },
    )

    @Test fun lexicalDateFilterPagesPastMoreRelevantWrongDatesBeforeApplyingLimit() = runBlocking {
        val wrongDay = capturedAt - 2 * 86_400_000L
        index.put((0 until 45).map { entry("wrong_$it", wrongDay, repeatedMatch = true) } +
            entry("unknown", null, repeatedMatch = true) + entry("eligible", capturedAt))
        val leading = index.search("dog", null, 20)
        assertTrue("Fixture must put the eligible photo beyond the first page", leading.none { it.path.endsWith("/eligible.jpg") })
        assertEquals(listOf("eligible.jpg"), index.search("dog", IndexPolicy.Kind.IMAGE, 1, filter).map { File(it.path).name })
        assertEquals(listOf("eligible.jpg"), index.search("dog", null, 1, filter).map { File(it.path).name })
    }

    @Test fun similarityDateFilterPagesPastHigherScoringWrongDatesBeforeApplyingLimit() = runBlocking {
        index.put((0 until 45).map { entry("wrong_$it", capturedAt - 2 * 86_400_000L) } +
            entry("unknown", null) + entry("eligible", capturedAt, score = 0.8f))
        assertTrue(index.searchSimilar(vector(1f), 0.5f, 20).none { it.path.endsWith("/eligible.jpg") })
        val result = index.searchSimilar(vector(1f), 0.5f, 1, filter).single()
        assertTrue(result.path.endsWith("/eligible.jpg"))
        assertEquals(0.8f, result.similarity!!, 0.01f)
    }
}
