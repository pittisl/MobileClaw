package edu.pitt.isl.mobileclaw.notiftest.skills.search

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.pitt.isl.mobileclaw.notiftest.AppSettings
import edu.pitt.isl.mobileclaw.notiftest.Capability
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Explicit setup acceptance, not a default suite case: rebuilds the real phone's index
 * and invokes its real search tool against preloaded shared-storage files. Requires
 * tools/demo_preload.sh and user-granted all-files access. Run with -e phonePreload true.
 * No LLM requests, SMS, contact/calendar writes or permission changes are made here.
 */
@RunWith(AndroidJUnit4::class)
class DemoPhoneAcceptanceRunner {
    @Test
    fun preloadedSharedStorageFilesReachTheRealSearchTool() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("phonePreload") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue("Grant all-files access explicitly before acceptance", Capability.ALL_FILES_ACCESS.isGranted(context))
        val root = Environment.getExternalStorageDirectory()
        val paths = listOf(
            "Download/MobileClawDemo/hotel-confirmation.pdf",
            "Download/MobileClawDemo/course-handout.pdf",
            "Download/MobileClawDemo/meeting-note.pdf",
            "DCIM/Camera/MobileClawDemo/boarding-pass.png",
            "DCIM/Camera/MobileClawDemo/restaurant-card.png",
        )
        paths.forEach { assertTrue("Missing preload: $it", File(root, it).isFile) }
        AppSettings.init(context)
        assertTrue("Enable photo/PDF content indexing before acceptance", AppSettings.labelPhotos)
        val started = SystemClock.elapsedRealtime()
        val outcome = FileIndexer(context, photoContent = PhotoContent.forWorker(context, true)).rebuild()
        assertNull(outcome.error, outcome.error)
        assertTrue("Expected at least the 31 corpus files: $outcome", outcome.filesIndexed >= 31)
        Log.i(TAG, "production rebuild: ${outcome.filesIndexed} files, ${outcome.imagesAnalysed} photos/PDFs, " +
            "${outcome.imagesPending} pending, ${SystemClock.elapsedRealtime() - started} ms")
        val tool = SearchFilesTool(context)
        for ((query, kind, name, facts) in listOf(
            Case("LKVQ7X", "document", "hotel-confirmation.pdf", listOf("Cedar House Austin", "1,236.80")),
            Case("CedarDemo", "document", "hotel-confirmation.pdf", listOf("CedarDemo")),
            Case("UA1234", "image", "boarding-pass.png", listOf("09:40", "B18", "12A")),
            Case("JNPR8K", "image", "restaurant-card.png", listOf("Juniper Table", "18:30")),
            Case("Benedum", "document", "course-handout.pdf", listOf("Benedum 204")),
            Case("return-call", "document", "meeting-note.pdf", listOf("15:45", "16:15")),
        )) {
            val requestStarted = SystemClock.elapsedRealtime()
            val result = tool.invoke(JSONObject().put("query", query).put("kind", kind).put("limit", 8))
            assertTrue("$query did not find $name: $result", result.contains(name))
            assertTrue(result, result.contains("text read from it by OCR"))
            facts.forEach { fact -> assertTrue("Missing '$fact' for $query: $result", result.contains(fact, true)) }
            Log.i(TAG, "$query -> $name: ${SystemClock.elapsedRealtime() - requestStarted} ms; facts verified")
        }
    }

    private data class Case(val query: String, val kind: String, val name: String, val facts: List<String>)
    private companion object {
        const val TAG = "MobileClaw-DEMO-OCR"
    }
}
