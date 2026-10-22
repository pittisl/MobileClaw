package edu.pitt.isl.mobileclaw.notiftest.demo

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.pitt.isl.mobileclaw.notiftest.AgentRunState
import edu.pitt.isl.mobileclaw.notiftest.AppSettings
import edu.pitt.isl.mobileclaw.notiftest.NotifListener
import edu.pitt.isl.mobileclaw.notiftest.skills.calendar.ReadCalendarTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated provider fixtures exercise setup and the production reader without a live model or call. */
@RunWith(AndroidJUnit4::class)
class DemoCalendarTest {
    @Test fun repeatedScenarioSetupPreservesOtherFixturesAndUserEventsAndScopesTheRealReader() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val priorSource = prefs.getString(AppSettings.KEY_CALENDAR_SOURCE, null)
        val priorPaused = AgentRunState.isPaused
        val suffix = System.nanoTime()
        val shared = DemoCalendar(context, "MobileClawDemoTest-$suffix", "Shared demo test")
        val personal = DemoCalendar(context, "MobileClawPersonalDecoyTest-$suffix", "Personal decoy test")
        val created = mutableListOf<Pair<DemoCalendar, Long>>()
        val start = 2366841600000L
        val minute = 60_000L
        val cr = context.contentResolver
        ui.adoptShellPermissionIdentity(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        try {
            AgentRunState.setPaused(true)
            withContext(Dispatchers.Main) {
                NotifListener.instance?.beginDemoReset()
                NotifListener.instance?.drainDemoRuns()
            }
            val id = shared.ensure().also { created += shared to it }
            val personalId = personal.ensure().also { created += personal to it }
            assertEquals("Repeated setup must reuse the shared calendar", id, shared.ensure())
            val scenarios = mapOf(
                "general" to listOf("meeting", "dentist", "deadline", "flight"),
                "hotel" to listOf("arrival", "hotel-arrival"),
                "schedule" to listOf("poster", "setup", "research")
            )
            for ((scenario, keys) in scenarios) shared.refreshScenario(id, scenario, keys.mapIndexed { i, key ->
                if (scenario == "general" && key == "meeting") {
                    // This meeting starts before the search window and overlaps callback review.
                    DemoCalendar.Fixture(key, "Shared general meeting", start - 30 * minute, start + 60 * minute)
                } else if (scenario == "schedule" && key == "research") {
                    // The nine-minute gap after callback review is too short for a full callback.
                    DemoCalendar.Fixture(key, "Shared schedule research", start + 90 * minute, start + 100 * minute)
                } else DemoCalendar.Fixture(key, "Shared $scenario $key", start + i * minute, start + (i + 1) * minute)
            })
            fun unmarked(title: String): Long = ContentUris.parseId(requireNotNull(cr.insert(
                CalendarContract.Events.CONTENT_URI, ContentValues().apply {
                    put("calendar_id", id); put("title", title); put("dtstart", start); put("dtend", start + minute)
                    put("eventTimezone", "UTC"); put("eventLocation", "User-owned location")
                    put("description", "User-owned details; setup must preserve them")
                })))
            val testEventId = unmarked("Test event")
            // A user event with the fixture's title still belongs to the user.
            val sameTitleId = unmarked("MobileClaw demo meeting")
            personal.refreshScenario(personalId, "personal", listOf(
                DemoCalendar.Fixture("private", "Excluded personal decoy", start, start + minute)))
            val personalBefore = personal.events(personalId)
            val othersBefore = shared.events(id)
            val first = shared.refreshScenario(id, "callback", listOf(
                DemoCalendar.Fixture("meeting", "MobileClaw demo meeting", start, start + 20 * minute),
                DemoCalendar.Fixture("review", "MobileClaw demo review", start + 50 * minute, start + 80 * minute),
                DemoCalendar.Fixture("obsolete", "Obsolete owned callback", start, start + minute)
            ))
            val newFixtures = listOf(
                DemoCalendar.Fixture("meeting", "MobileClaw demo meeting", start + minute, start + 21 * minute),
                DemoCalendar.Fixture("review", "MobileClaw demo review", start + 51 * minute, start + 81 * minute)
            )
            val refreshed = shared.refreshScenario(id, "callback", newFixtures)
            val repeated = shared.refreshScenario(id, "callback", newFixtures)
            assertEquals(first.take(2).map { it.id }, refreshed.map { it.id })
            assertEquals("A repeat must not duplicate or rewrite other fixtures", refreshed, repeated)
            val slot = shared.firstFreeSlot(id, start + 21 * minute)
            assertEquals("Overlapping meetings and a gap shorter than 15 minutes must all block the slot", start + 100 * minute, slot.startMs)
            assertEquals(start + 115 * minute, slot.endMs)
            val after = shared.events(id)
            assertEquals(othersBefore, after.filter { it.marker?.startsWith("${DemoCalendar.MARKER_PREFIX}callback.") != true })
            assertTrue(after.any { it.id == testEventId && it.marker == null })
            assertTrue(after.any { it.id == sameTitleId && it.marker == null })
            assertFalse(after.any { it.id == first.last().id })
            assertEquals("Personal calendar was changed", personalBefore, personal.events(personalId))
            assertEquals(id, shared.select())
            val result = ReadCalendarTool(context).invoke(JSONObject().put("range_start", (start - minute).toString())
                .put("range_end", (start + 120 * minute).toString()))
            after.forEach { assertTrue("Merged fixture missing from production reader: ${it.id}", result.contains(it.renderedInterval())) }
            assertTrue("The excluded decoy must still exist", personal.events(personalId).single().title == "Excluded personal decoy")
            assertFalse("The shared source exposed a personal calendar", result.contains("Excluded personal decoy"))
            assertEquals("Reader must not change selection", id.toString(), AppSettings.calendarReadSource)
        } finally {
            try {
                created.forEach { (demo, id) -> cr.delete(demo.syncUri(ContentUris.withAppendedId(
                    CalendarContract.Calendars.CONTENT_URI, id)), null, null) }
            } finally {
                if (priorSource == null) prefs.edit().remove(AppSettings.KEY_CALENDAR_SOURCE).commit()
                else prefs.edit().putString(AppSettings.KEY_CALENDAR_SOURCE, priorSource).commit()
                NotifListener.instance?.finishDemoReset()
                AgentRunState.setPaused(priorPaused)
                ui.dropShellPermissionIdentity()
            }
        }
    }
}
