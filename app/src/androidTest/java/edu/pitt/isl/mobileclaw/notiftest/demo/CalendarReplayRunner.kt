package edu.pitt.isl.mobileclaw.notiftest.demo

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.pitt.isl.mobileclaw.notiftest.*
import edu.pitt.isl.mobileclaw.notiftest.skills.calendar.ReadCalendarTool
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in replay of an archived actual suggestion; the host then clicks its real UI chip. */
@RunWith(AndroidJUnit4::class)
class CalendarReplayRunner {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun output(name: String, report: JSONObject) {
        File(context.filesDir, "calendar-replay/$name.json").apply {
            parentFile!!.mkdirs()
            writeText(report.toString(2) + "\n")
        }
    }

    @Test fun restoreArchivedJimSuggestionWithoutModel() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("calendarReplay") == "true")
        val db = MobileClawDb.get(context)
        val source = JSONObject(File(context.filesDir, "calendar-replay/archived-source.json").readText())
        val content = source.getString("content")
        val timestamp = source.getLong("capturedAtMs")
        val actions = ChatConverters.toActions(source.getJSONArray("actions").toString())
        assertTrue("Replay must identify its captured source", source.getString("sourceArchive").isNotBlank())
        assertTrue(content.contains("Jim"))
        assertTrue(actions.contains("Add to calendar"))
        ChatStore.init(context)
        AppSettings.init(context)
        val priorSession = ChatStore.activeSessionId.value
        val priorPaused = AgentRunState.isPaused
        AgentRunState.setPaused(true)
        val replay = ChatStore.newSession()
        ChatStore.rename(replay, "Calendar verification")
        ChatStore.add(ChatMessage(ChatMessage.Role.ASSISTANT, content,
            timestampMs = timestamp, actions = actions))
        withTimeout(5_000) {
            while (db.chatMessages().count(replay) != 1 || db.chatSessions().find(replay) == null) delay(20)
        }
        output("source", JSONObject().put("sourceArchive", source.getString("sourceArchive"))
            .put("sourceTimeKind", "archived rehearsal capture time").put("sourceTimestampMs", timestamp)
            .put("sourceContent", content).put("replaySessionId", replay)
            .put("priorSessionId", priorSession).put("priorPaused", priorPaused)
            .put("calendarSource", AppSettings.calendarReadSource).put("liveModelCalled", false))
    }

    @Test fun verifyCreatedEventWithProductionReader() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("calendarReplay") == "true")
        val eventId = requireNotNull(args.getString("eventId")?.toLongOrNull())
        AppSettings.init(context)
        val expectedCalendar = requireNotNull(AppSettings.calendarReadSource.toLongOrNull())
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val report = JSONObject().put("eventId", eventId).put("calendarSource", expectedCalendar)
        context.contentResolver.query(uri, arrayOf("calendar_id", "title", "dtstart", "dtend", "eventLocation"),
            null, null, null)!!.use { cursor ->
            assertTrue("The confirmed event must exist", cursor.moveToFirst())
            assertEquals(expectedCalendar, cursor.getLong(0))
            val title = cursor.getString(1)
            val start = cursor.getLong(2)
            val end = cursor.getLong(3)
            val location = cursor.getString(4)
            assertTrue(location.contains("Jim", ignoreCase = true))
            val readBack = ReadCalendarTool(context).invoke(JSONObject()
                .put("range_start", (start - 1).toString()).put("range_end", (end + 1).toString()))
            assertTrue("Production reader must return the confirmed event", readBack.contains(title))
            assertTrue(readBack.contains(location))
            report.put("title", title).put("startMs", start).put("endMs", end)
                .put("location", location).put("readerResult", readBack)
        }
        output("verified", report.put("passed", true).put("liveModelCalled", false))
    }
}
