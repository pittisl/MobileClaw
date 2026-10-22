package edu.pitt.isl.mobileclaw.notiftest.skills.search

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.pitt.isl.mobileclaw.notiftest.Capability
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * The tool over a real index: the one behaviour the index alone cannot show is
 * the `kind` fallback, which exists because the first live run on `mobileclaw-34` had the
 * model ask for `kind: "document"` for a receipt that was a `.txt` and get "no files match".
 *
 * Grants all-files access to itself through the instrumentation shell — like
 * `NotifConfirmTest` grants `POST_NOTIFICATIONS` — and skips rather than fails if the grant
 * did not take, since that is the device refusing, not the tool.
 */
@RunWith(AndroidJUnit4::class)
class SearchFilesToolTest {

    private lateinit var context: Context
    private lateinit var fixture: File
    private lateinit var index: FileIndex

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow").close()
        assumeTrue("all-files access could not be granted", Capability.ALL_FILES_ACCESS.isGranted(context))
        fixture = File(context.getExternalFilesDir(null), "tool-fixtures").apply { deleteRecursively(); mkdirs() }
        File(fixture, "hotel_receipt-austin.txt").writeText("Hotel receipt. Austin, TX. Total 389.20 USD.")
        index = FileIndex(context, databaseName = "mobileclaw-files-tooltest")
        runBlocking { FileIndexer(context, index, fixture, PhotoContent.Off).rebuild() }
    }

    @After
    fun tearDown() {
        if (this::index.isInitialized) runBlocking { index.clear() }
        if (this::fixture.isInitialized) fixture.deleteRecursively()
    }

    @Test
    fun aWrongKindGuessWidensInsteadOfMissing() {
        val out = runBlocking {
            SearchFilesTool(context, index).invoke(JSONObject("""{"query":"hotel receipt austin","kind":"document"}"""))
        }
        assertTrue(out, out.contains("hotel_receipt-austin.txt"))
        assertTrue(out, out.contains("No document files match; showing every kind."))
    }

    @Test
    fun aRightKindNeedsNoNote() {
        val out = runBlocking {
            SearchFilesTool(context, index).invoke(JSONObject("""{"query":"receipt","kind":"text"}"""))
        }
        assertTrue(out, out.contains("hotel_receipt-austin.txt"))
        assertTrue(out, !out.contains("showing every kind"))
    }

    @Test
    fun aWordTheFileLacksLoosensTheQueryInsteadOfMissing() {
        // The search includes contextual words that do not occur in the target document.
        val out = runBlocking {
            SearchFilesTool(context, index).invoke(JSONObject("""{"query":"hotel receipt MobiCom","kind":"document"}"""))
        }
        assertTrue(out, out.contains("hotel_receipt-austin.txt"))
        assertTrue(out, out.contains("No file matches every word of \"hotel receipt MobiCom\"; showing files that match some of them"))
    }

    @Test
    fun aQueryEveryWordOfWhichMatchesIsNotLoosened() {
        val out = runBlocking {
            SearchFilesTool(context, index).invoke(JSONObject("""{"query":"hotel receipt austin"}"""))
        }
        assertTrue(out, out.contains("hotel_receipt-austin.txt"))
        assertTrue(out, !out.contains("match some of them"))
    }

    @Test
    fun dateSurvivesBothWordAndKindFallbacksAndUnknownDatesStayExcluded() = runBlocking {
        val day = LocalDate.of(2026, 10, 14).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        fun photo(name: String, captured: Long?) = FileIndex.Entry(
            File(fixture, name).path, name, IndexPolicy.Kind.IMAGE, 100, day,
            "Pictures", null, PhotoFeatures(listOf(IndexPolicy.Label("dog", 0.9f)), null, embedded = false), captured, "EXIF",
        )
        index.put(listOf(photo("eligible.jpg", day + 1000), photo("wrong_day.jpg", day - 1000), photo("unknown.jpg", null)))
        val out = SearchFilesTool(context, index).invoke(JSONObject(
            """{"query":"dog unavailableword","kind":"document","captured_on":"2026-10-14"}"""
        ))
        assertTrue(out, out.contains("eligible.jpg"))
        assertTrue(out, out.contains("match some of them"))
        assertTrue(out, !out.contains("wrong_day.jpg"))
        assertTrue(out, !out.contains("unknown.jpg"))
        assertTrue(out, out.contains("unknown capture dates are excluded"))
        val miss = SearchFilesTool(context, index).invoke(JSONObject(
            """{"query":"dog unavailableword","kind":"document","captured_on":"2026-10-16"}"""
        ))
        assertTrue(miss, miss.contains("No files match"))
        assertTrue(miss, !miss.contains("eligible.jpg"))
    }

    @Test
    fun invalidDateDoesNotSilentlyBecomeAnUnfilteredSearch() = runBlocking {
        val out = SearchFilesTool(context, index).invoke(JSONObject(
            """{"query":"receipt","captured_on":"2026-02-30"}"""
        ))
        assertTrue(out, out.contains("No search was performed"))
        assertTrue(out, !out.contains("hotel_receipt-austin.txt"))
    }
}
