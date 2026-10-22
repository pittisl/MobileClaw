package edu.pitt.isl.mobileclaw.notiftest

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Covers the export path end to end, and in particular the WAL checkpoint.
 *
 * Room runs in WAL mode, so a freshly-written row lives in `mobileclaw.db-wal` and
 * the `.db` file alone is a stale snapshot. Copying it without checkpointing
 * produces a file that opens fine and is simply missing the newest rows — a silent
 * failure a user exporting their audit trail would never catch. This test is the
 * automated form of that check: write a row, export, then open the *exported copy*
 * as an independent database and demand the row is in it.
 */
@RunWith(AndroidJUnit4::class)
class DataExportTest {

    private lateinit var context: Context
    private lateinit var destination: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ActionLogStore.init(context)
        ActionLogStore.clear()
        awaitUntil(message = "store did not clear") { ActionLogStore.entries.value.isEmpty() }
        destination = File(context.cacheDir, "export-test.db").also { it.delete() }
    }

    @After
    fun tearDown() {
        destination.delete()
        ActionLogStore.clear()
    }

    @Test
    fun export_producesAReadableCopyContainingTheNewestRow() = runTest {
        val marker = "ExportMarker-${System.currentTimeMillis()}"
        ActionLogStore.record(marker, "k|1", "Archive", null, "Pressed \"Archive\".", true)
        awaitUntil(message = "record never landed") { ActionLogStore.entries.value.isNotEmpty() }

        val written = DataExport.exportTo(context, Uri.fromFile(destination)).getOrThrow()

        assertTrue("export wrote nothing", written > 0)
        assertEquals(written, destination.length())

        // Open the exported file as its own database — no WAL sidecars alongside it,
        // so anything not checkpointed into the main file is simply not here.
        val copy = SQLiteDatabase.openDatabase(
            destination.absolutePath, null, SQLiteDatabase.OPEN_READONLY
        )
        try {
            copy.rawQuery("SELECT appName FROM action_log", null).use { c ->
                assertEquals("expected exactly one audit row in the export", 1, c.count)
                assertTrue(c.moveToFirst())
                assertEquals(marker, c.getString(0))
            }
        } finally {
            copy.close()
        }
    }

    @Test
    fun formatSize_isHumanReadable() {
        assertEquals("512 bytes", DataExport.formatSize(512))
        assertEquals("1.0 KB", DataExport.formatSize(1024))
        assertEquals("2.0 MB", DataExport.formatSize(2 * 1024 * 1024))
    }

    /** The suggested name must be a distinct, sortable `.db` file per export. */
    @Test
    fun suggestedFileName_isTimestampedAndUnique() {
        val name = DataExport.suggestedFileName()
        assertTrue(name, name.startsWith("mobileclaw-export-"))
        assertTrue(name, name.endsWith(".db"))
        assertTrue(name, Regex("""mobileclaw-export-\d{8}-\d{6}\.db""").matches(name))
    }
}
