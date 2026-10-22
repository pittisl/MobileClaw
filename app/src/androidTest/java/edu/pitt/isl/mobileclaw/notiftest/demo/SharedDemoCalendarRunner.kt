package edu.pitt.isl.mobileclaw.notiftest.demo

import android.content.Context
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.pitt.isl.mobileclaw.notiftest.AppSettings
import edu.pitt.isl.mobileclaw.notiftest.Capability
import edu.pitt.isl.mobileclaw.notiftest.skills.calendar.ReadCalendarTool
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.TimeZone

/** Explicit setup: persist the shared source and check the actual reader, without a model turn. */
@RunWith(AndroidJUnit4::class)
class SharedDemoCalendarRunner {
    @Test fun selectAndVerifyMergedCalendarWithoutModel() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with -e demoCalendarScope true", args.getString("demoCalendarScope") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val out = File(context.filesDir, "demo-calendar/calendar-scope.json").apply { parentFile!!.mkdirs() }
        val report = JSONObject().put("liveLlm", false).put("timezone", TimeZone.getDefault().id)
        try {
            assertTrue("Grant MobileClaw Calendar access before setup", Capability.READ_CALENDAR.isGranted(context))
            val demo = DemoCalendar(context)
            val id = demo.select()
            val before = demo.events(id)
            report.put("calendarId", id).put("calendarSource", AppSettings.calendarReadSource)
                .put("eventIds", JSONArray(before.map { it.id })).put("eventCount", before.size)
            for (legacy in DemoCalendar.LEGACY_ACCOUNTS) {
                context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf("_id"),
                    "account_name=? AND account_type=? AND name=?", arrayOf(legacy, "LOCAL", legacy), null)!!.use {
                    assertEquals("Legacy demo calendar remains: $legacy", 0, it.count)
                }
            }
            report.put("legacyCalendarsRemoved", true)
            val expectedIds = args.getString("demoCalendarExpectedEventIds")?.split(',')?.map {
                requireNotNull(it.trim().toLongOrNull()) { "Expected event ids must be comma-separated integers" }
            }?.toSet()
            if (expectedIds != null) assertEquals("An existing event was lost or unexpectedly added", expectedIds, before.map { it.id }.toSet())
            val expectedTitles = mapOf(
                "hotel" to listOf("Arrive in Austin — UA1234", "Planned arrival at Cedar House Austin"),
                "schedule" to listOf("Poster review", "Demo setup", "Research meeting"),
                "callback" to listOf("MobileClaw demo meeting", "MobileClaw demo review")
            )
            val reader = ReadCalendarTool(context)
            val results = JSONObject()
            report.put("readerResults", results)
            for ((scenario, titles) in expectedTitles) {
                val fixtures = demo.requiredFixtures(scenario, titles)
                val text = reader.invoke(JSONObject().put("range_start", (fixtures.minOf { it.startMs } - 1).toString())
                    .put("range_end", (fixtures.maxOf { it.endMs } + 1).toString()))
                fixtures.forEach { assertTrue("$scenario reader omitted event ${it.id}", text.contains(it.renderedInterval())) }
                results.put(scenario, JSONObject().put("eventIds", JSONArray(fixtures.map { it.id })).put("result", text))
            }
            // General fixtures have configurable dates/titles; their reserved keys identify ownership.
            val general = before.filter { it.marker?.startsWith("${DemoCalendar.MARKER_PREFIX}general.") == true }
            val generalKeys = general.map { it.marker!!.substringAfterLast('.') }.toSet()
            assertTrue("General demo fixtures missing", generalKeys.containsAll(listOf("meeting", "dentist", "deadline", "flight")))
            val generalResults = JSONArray()
            for (event in general) {
                val text = reader.invoke(JSONObject().put("range_start", (event.startMs - 1).toString())
                    .put("range_end", (event.endMs + 1).toString()))
                assertTrue("General reader omitted event ${event.id}", text.contains(event.renderedInterval()))
                generalResults.put(JSONObject().put("eventId", event.id).put("result", text))
            }
            results.put("general", generalResults)
            val userTest = before.filter { it.title == "Test event" && it.marker == null }
            assertEquals("Preserve the user's unmarked Test event", 1, userTest.size)
            val event = userTest.single()
            val testResult = reader.invoke(JSONObject().put("range_start", (event.startMs - 1).toString())
                .put("range_end", (event.endMs + 1).toString()))
            assertTrue(testResult.contains(event.renderedInterval()))
            report.put("userTestEventId", event.id).put("userTestEventPreserved", true).put("userTestReader", testResult)
            assertEquals("Reader verification must not rewrite events", before, demo.events(id))
            assertEquals(id.toString(), AppSettings.calendarReadSource)
            report.put("status", "passed").put("eventsUnchanged", true)
        } catch (t: Throwable) {
            report.put("status", "failed").put("error", "${t.javaClass.simpleName}: ${t.message}")
            throw t
        } finally {
            out.writeText(report.toString(2) + "\n")
        }
    }
}
