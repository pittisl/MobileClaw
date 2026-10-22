package edu.pitt.isl.mobileclaw.notiftest

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.pitt.isl.mobileclaw.notiftest.skills.calendar.ReadCalendarTool
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real provider selection, including a decoy calendar and fail-closed missing ids. No model calls. */
@RunWith(AndroidJUnit4::class)
class CalendarReadScopeTest {
    @Test fun selectedCalendarExcludesOtherCalendarsAndNeverFallsBack() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val key = AppSettings.KEY_CALENDAR_SOURCE
        val prior = prefs.getString(key, null)
        val paused = AgentRunState.isPaused
        val ids = mutableListOf<Long>()
        val account = "MobileClawScopeTest-${System.nanoTime()}"
        val resolver = context.contentResolver
        ui.adoptShellPermissionIdentity(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        try {
            AgentRunState.setPaused(true)
            withContext(Dispatchers.Main) {
                NotifListener.instance?.beginDemoReset()
                NotifListener.instance?.drainDemoRuns()
            }
            for (name in listOf("AllowedFixture", "ExcludedDecoy")) {
                val values = ContentValues().apply {
                    put("account_name", account); put("account_type", "LOCAL")
                    put("name", name); put("calendar_displayName", name)
                    put("ownerAccount", account); put("calendar_access_level", 700)
                    put("visible", 1); put("sync_events", 1); put("calendar_timezone", "UTC")
                }
                val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
                    .appendQueryParameter("caller_is_syncadapter", "true")
                    .appendQueryParameter("account_name", account).appendQueryParameter("account_type", "LOCAL").build()
                val id = ContentUris.parseId(requireNotNull(resolver.insert(uri, values)))
                ids += id
                resolver.insert(CalendarContract.Events.CONTENT_URI, ContentValues().apply {
                    put("calendar_id", id); put("title", name)
                    put("dtstart", 2366841600000L); put("dtend", 2366845200000L); put("eventTimezone", "UTC")
                })
            }
            val tool = ReadCalendarTool(context)
            val range = JSONObject().put("range_start", "2366838000000").put("range_end", "2366848800000")
            prefs.edit().putString(key, "all").commit()
            val all = tool.invoke(range)
            assertTrue(all.contains("AllowedFixture")); assertTrue(all.contains("ExcludedDecoy"))
            prefs.edit().putString(key, ids[0].toString()).commit()
            val selected = tool.invoke(range)
            assertTrue(selected.contains("AllowedFixture")); assertFalse(selected.contains("ExcludedDecoy"))
            val formatter = java.text.SimpleDateFormat("EEE d MMM yyyy, HH:mm", java.util.Locale.US)
            assertTrue("Event interval must include the actual provider END", selected.contains(
                "${formatter.format(java.util.Date(2366841600000L))} – ${formatter.format(java.util.Date(2366845200000L))} — AllowedFixture"))
            prefs.edit().putString(key, Long.MAX_VALUE.toString()).commit()
            val missing = tool.invoke(range)
            assertTrue(missing.contains("Selected calendar is unavailable")); assertFalse(missing.contains("AllowedFixture"))
            prefs.edit().putString(key, "invalid").commit()
            assertTrue(tool.invoke(range).contains("no events were read"))
        } finally {
            try {
                ids.forEach { id ->
                    val uri = ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, id).buildUpon()
                        .appendQueryParameter("caller_is_syncadapter", "true")
                        .appendQueryParameter("account_name", account).appendQueryParameter("account_type", "LOCAL").build()
                    resolver.delete(uri, null, null)
                }
            } finally {
                if (prior == null) prefs.edit().remove(key).commit() else prefs.edit().putString(key, prior).commit()
                NotifListener.instance?.finishDemoReset()
                AgentRunState.setPaused(paused)
                ui.dropShellPermissionIdentity()
            }
        }
    }
}
