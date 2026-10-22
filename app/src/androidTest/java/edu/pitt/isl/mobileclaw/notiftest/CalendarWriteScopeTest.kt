package edu.pitt.isl.mobileclaw.notiftest

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.pitt.isl.mobileclaw.notiftest.skills.calendar.CreateEventTool
import edu.pitt.isl.mobileclaw.notiftest.skills.calendar.ReadCalendarTool
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real CalendarProvider writes, a decoy primary calendar, and changes during confirmation. No model calls. */
@RunWith(AndroidJUnit4::class)
class CalendarWriteScopeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private inner class Fixture {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val account = "MobileClawWriteScope-${System.nanoTime()}"
        val ids = mutableListOf<Long>()
        val primary = calendar("Other primary calendar", primary = true)
        val selected = calendar("Selected demo calendar")
        val title = "Meet Jim calendar scope fixture"
        val args: JSONObject get() = JSONObject().put("title", title)
            .put("start_time", "2366841600000").put("end_time", "2366845200000")
            .put("location", "Jim's office")

        fun select(source: String = selected.toString()) {
            prefs.edit().putString(AppSettings.KEY_CALENDAR_SOURCE, source).commit()
        }

        private fun calendar(name: String, primary: Boolean = false): Long {
            val values = ContentValues().apply {
                put("account_name", account); put("account_type", "LOCAL")
                put("name", name); put("calendar_displayName", name)
                put("ownerAccount", account); put("calendar_access_level", 700)
                put("visible", 1); put("sync_events", 1); put("calendar_timezone", "UTC")
                put("isPrimary", if (primary) 1 else 0)
            }
            return ContentUris.parseId(requireNotNull(context.contentResolver.insert(
                syncUri(), values))).also { ids += it }
        }

        private fun syncUri(id: Long? = null) =
            (id?.let { ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, it) }
                ?: CalendarContract.Calendars.CONTENT_URI).buildUpon()
                .appendQueryParameter("caller_is_syncadapter", "true")
                .appendQueryParameter("account_name", account)
                .appendQueryParameter("account_type", "LOCAL").build()

        fun makeReadOnly() {
            assertEquals(1, context.contentResolver.update(syncUri(selected), ContentValues().apply {
                put("calendar_access_level", CalendarContract.Calendars.CAL_ACCESS_READ)
            }, null, null))
        }

        fun deleteSelected() {
            assertEquals(1, context.contentResolver.delete(syncUri(selected), null, null))
        }

        fun eventCalendars(): List<Long> = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events.CALENDAR_ID),
            "${CalendarContract.Events.TITLE} = ? AND ${CalendarContract.Events.CALENDAR_ID} IN (?, ?)",
            arrayOf(title, primary.toString(), selected.toString()), null
        )!!.use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }

        suspend fun proposal(tool: CreateEventTool = CreateEventTool(context)): PendingAction.Proposal = coroutineScope {
            val waiting = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(5_000) { PendingAction.proposals.first { it.description.contains(title) } }
            }
            try {
                assertTrue(tool.invoke(args).startsWith("Prepared a calendar event"))
                waiting.await()
            } finally {
                waiting.cancel()
            }
        }

        fun clean() { ids.forEach { context.contentResolver.delete(syncUri(it), null, null) } }
    }

    private suspend fun fixture(block: suspend Fixture.() -> Unit) {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val source = prefs.getString(AppSettings.KEY_CALENDAR_SOURCE, null)
        val hadAutomation = prefs.contains(AppSettings.KEY_FULL_AUTOMATION)
        val automation = prefs.getBoolean(AppSettings.KEY_FULL_AUTOMATION, false)
        val paused = AgentRunState.isPaused
        AppSettings.init(context)
        ui.adoptShellPermissionIdentity(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        var fixture: Fixture? = null
        try {
            AgentRunState.setPaused(true)
            withContext(Dispatchers.Main) {
                NotifListener.instance?.beginDemoReset()
                NotifListener.instance?.drainDemoRuns()
            }
            prefs.edit().putBoolean(AppSettings.KEY_FULL_AUTOMATION, false).commit()
            val current = Fixture().also { fixture = it }
            current.select()
            ActivityScenario.launch(MainActivity::class.java).use {
                awaitUntil { AppForeground.isForeground }
                current.block()
            }
        } finally {
            try { fixture?.clean() } finally {
                prefs.edit().apply {
                    if (source == null) remove(AppSettings.KEY_CALENDAR_SOURCE)
                    else putString(AppSettings.KEY_CALENDAR_SOURCE, source)
                    if (hadAutomation) putBoolean(AppSettings.KEY_FULL_AUTOMATION, automation)
                    else remove(AppSettings.KEY_FULL_AUTOMATION)
                }.commit()
                NotifListener.instance?.finishDemoReset()
                AgentRunState.setPaused(paused)
                ui.dropShellPermissionIdentity()
            }
        }
    }

    @Test fun writesToSelectedCalendarOnlyAfterConfirmationAndTheReaderSeesIt() = runBlocking {
        fixture {
            val proposal = proposal()
            assertTrue(proposal.description.contains("Calendar: Selected demo calendar"))
            assertTrue(proposal.description.contains("Jim's office"))
            assertTrue("Preparing the event must not write it", eventCalendars().isEmpty())
            assertTrue(proposal.execute().contains("added"))
            assertEquals(listOf(selected), eventCalendars())
            val range = JSONObject().put("range_start", "2366838000000").put("range_end", "2366848800000")
            assertTrue(ReadCalendarTool(context).invoke(range).contains(title))
            select(primary.toString())
            assertFalse(ReadCalendarTool(context).invoke(range).contains(title))
        }
    }

    @Test fun allInvalidMissingAndReadOnlySelectionsNeverWriteToThePrimary() = runBlocking {
        fixture {
            val tool = CreateEventTool(context)
            for (value in listOf("all", "invalid", Long.MAX_VALUE.toString())) {
                select(value)
                val result = tool.invoke(args)
                assertFalse(result, result.startsWith("Prepared"))
                assertTrue(eventCalendars().isEmpty())
            }
            select()
            makeReadOnly()
            assertTrue(tool.invoke(args).contains("read-only"))
            assertTrue(eventCalendars().isEmpty())
        }
    }

    @Test fun changingTheSelectionWhileConfirmationIsOpenDoesNotRedirectTheWrite() = runBlocking {
        fixture {
            val proposal = proposal()
            select(primary.toString())
            assertTrue(proposal.execute().contains("changed before confirmation"))
            assertTrue(eventCalendars().isEmpty())
        }
    }

    @Test fun aDeletedDestinationAtConfirmationDoesNotFallBack() = runBlocking {
        fixture {
            val proposal = proposal()
            deleteSelected()
            assertTrue(proposal.execute().contains("unavailable"))
            assertTrue(eventCalendars().isEmpty())
        }
    }

    @Test fun losingCalendarWriteAccessBeforeConfirmationDoesNotFallBack() = runBlocking {
        fixture {
            val proposal = proposal()
            makeReadOnly()
            assertTrue(proposal.execute().contains("read-only"))
            assertTrue(eventCalendars().isEmpty())
        }
    }

    @Test fun missingReadPermissionIsReportedBeforeQueryingCalendars() = runBlocking {
        fixture {
            val denied = object : ContextWrapper(context) {
                override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                    if (permission == Manifest.permission.READ_CALENDAR) PackageManager.PERMISSION_DENIED
                    else super.checkPermission(permission, pid, uid)
                override fun checkSelfPermission(permission: String): Int =
                    checkPermission(permission, android.os.Process.myPid(), android.os.Process.myUid())
            }
            assertEquals(Capability.unavailable(Capability.READ_CALENDAR), CreateEventTool(denied).invoke(args))
            assertTrue(eventCalendars().isEmpty())
        }
    }

    @Test fun revokingWritePermissionWhileConfirmationIsOpenPreventsTheWrite() = runBlocking {
        fixture {
            var allowed = true
            val revocable = object : ContextWrapper(context) {
                override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                    if (!allowed && permission == Manifest.permission.WRITE_CALENDAR) PackageManager.PERMISSION_DENIED
                    else super.checkPermission(permission, pid, uid)
                override fun checkSelfPermission(permission: String): Int =
                    checkPermission(permission, android.os.Process.myPid(), android.os.Process.myUid())
            }
            val proposal = proposal(CreateEventTool(revocable))
            allowed = false
            assertTrue(proposal.execute().contains("permission changed"))
            assertTrue(eventCalendars().isEmpty())
        }
    }
}
