package edu.pitt.isl.mobileclaw.notiftest.demo

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import androidx.preference.PreferenceManager
import edu.pitt.isl.mobileclaw.notiftest.AppSettings
import edu.pitt.isl.mobileclaw.notiftest.ReasoningModule
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One local demo calendar; a scenario owns marked fixtures, never the whole calendar. */
class DemoCalendar(
    private val context: Context,
    private val account: String = ACCOUNT,
    private val displayName: String = DISPLAY_NAME,
) {
    companion object {
        const val ACCOUNT = "MobileClawDemo"
        const val DISPLAY_NAME = "MobileClaw demo"
        const val MARKER_PREFIX = "mobileclaw-demo."
        val LEGACY_ACCOUNTS = listOf("MobileClawHotelDemo", "MobileClawScheduleDemo", "MobileClawCallbackDemo")
    }

    data class Fixture(val key: String, val title: String, val startMs: Long, val endMs: Long,
        val location: String = "Fictional demo room")

    data class Slot(val startMs: Long, val endMs: Long)

    data class Event(val id: Long, val marker: String?, val title: String, val startMs: Long,
        val endMs: Long, val location: String?, val timezone: String?, val description: String?) {
        fun renderedInterval(): String {
            val fmt = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.US)
            return "${fmt.format(Date(startMs))} – ${fmt.format(Date(endMs))} — ${ReasoningModule.redact(title)}" +
                (location?.takeIf { it.isNotBlank() }?.let { " @ ${ReasoningModule.redact(it)}" } ?: "")
        }
    }

    fun syncUri(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account)
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, "LOCAL").build()

    private fun matchingIds(): List<Long> {
        val ids = mutableListOf<Long>()
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
            arrayOf("_id", "ownerAccount", "calendar_displayName"),
            "account_name=? AND account_type=? AND name=?", arrayOf(account, "LOCAL", account), null)!!.use {
            while (it.moveToNext()) {
                check(it.getString(1) == account && it.getString(2) == displayName) {
                    "Demo calendar identity does not match its owner and display name"
                }
                ids += it.getLong(0)
            }
        }
        check(ids.size <= 1) { "Multiple shared demo calendars found; consolidate before rehearsal" }
        return ids
    }

    fun requireExisting(): Long = checkNotNull(matchingIds().singleOrNull()) {
        "Prepare the shared MobileClaw demo calendar first"
    }

    fun ensure(): Long = matchingIds().singleOrNull() ?: ContentUris.parseId(requireNotNull(
        context.contentResolver.insert(syncUri(CalendarContract.Calendars.CONTENT_URI), ContentValues().apply {
            put("account_name", account); put("account_type", "LOCAL"); put("name", account)
            put("calendar_displayName", displayName); put("ownerAccount", account)
            put("calendar_color", -12627531); put("calendar_access_level", 700)
            put("visible", 1); put("sync_events", 1); put("calendar_timezone", TimeZone.getDefault().id)
        })))

    fun requireSelected(): Long = requireExisting().also {
        check(AppSettings.calendarReadSource == it.toString()) { "Select the shared MobileClaw demo calendar in Settings" }
    }

    fun select(): Long = requireExisting().also {
        check(PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(AppSettings.KEY_CALENDAR_SOURCE, it.toString()).commit())
        check(AppSettings.calendarReadSource == it.toString())
    }

    fun events(calendarId: Long = requireExisting()): List<Event> {
        check(calendarId == requireExisting()) { "Only the owned demo calendar may be inspected or refreshed" }
        val rows = mutableListOf<Event>()
        context.contentResolver.query(CalendarContract.Events.CONTENT_URI,
            arrayOf("_id", "_sync_id", "title", "dtstart", "dtend", "eventLocation", "eventTimezone", "description"),
            "calendar_id=? AND deleted=0", arrayOf(calendarId.toString()), "dtstart ASC, _id ASC")!!.use {
            while (it.moveToNext()) rows += Event(it.getLong(0), it.getString(1), it.getString(2).orEmpty(),
                it.getLong(3), it.getLong(4), it.getString(5), it.getString(6), it.getString(7))
        }
        return rows
    }

    fun requiredFixtures(scenario: String, titles: List<String>): List<Event> {
        val rows = events().filter { it.marker?.startsWith("$MARKER_PREFIX$scenario.") == true }
        return titles.map { title ->
            check(rows.count { it.title == title } == 1) { "Missing or duplicate $scenario fixture: $title" }
            rows.single { it.title == title }
        }
    }

    /** Instances includes recurring occurrences and events that begin before the search boundary. */
    fun firstFreeSlot(calendarId: Long, fromMs: Long, durationMs: Long = 15 * 60_000L): Slot {
        check(calendarId == requireExisting())
        val horizon = fromMs + 7 * 24 * 60 * 60_000L
        require(durationMs > 0 && durationMs <= horizon - fromMs)
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, fromMs)
            ContentUris.appendId(this, horizon)
        }.build()
        var candidate = fromMs
        context.contentResolver.query(uri, arrayOf(CalendarContract.Instances.BEGIN, CalendarContract.Instances.END),
            "${CalendarContract.Instances.CALENDAR_ID}=?", arrayOf(calendarId.toString()),
            "${CalendarContract.Instances.BEGIN} ASC")!!.use {
            while (it.moveToNext()) {
                val begin = it.getLong(0)
                val end = it.getLong(1)
                if (end <= candidate || end <= begin) continue
                if (begin >= candidate + durationMs) break
                // Moving only forward merges overlapping busy intervals and skips short gaps.
                candidate = end.coerceAtLeast(candidate)
            }
        }
        check(candidate + durationMs <= horizon) { "No complete callback slot in the next seven days" }
        return Slot(candidate, candidate + durationMs)
    }

    /** Hidden sync ids survive display-name edits, while an unmarked user event is never ours. */
    fun refreshScenario(calendarId: Long, scenario: String, fixtures: List<Fixture>): List<Event> {
        require(scenario.matches(Regex("[a-z][a-z0-9-]*")))
        require(fixtures.map { it.key }.distinct().size == fixtures.size)
        fixtures.forEach { require(it.key.matches(Regex("[a-z][a-z0-9-]*")) && it.endMs > it.startMs) }
        val prefix = "$MARKER_PREFIX$scenario."
        val existing = events(calendarId).filter { it.marker?.startsWith(prefix) == true }
        check(existing.map { it.marker }.distinct().size == existing.size) { "Duplicate owned fixture markers" }
        val wanted = fixtures.map { prefix + it.key }.toSet()
        val operations = arrayListOf<ContentProviderOperation>()
        for (event in existing.filter { it.marker !in wanted }) {
            operations += ContentProviderOperation.newDelete(syncUri(ContentUris.withAppendedId(
                CalendarContract.Events.CONTENT_URI, event.id))).build()
        }
        for (fixture in fixtures) {
            val marker = prefix + fixture.key
            val values = ContentValues().apply {
                put("calendar_id", calendarId); put("_sync_id", marker); put("title", fixture.title)
                put("dtstart", fixture.startMs); put("dtend", fixture.endMs)
                put("eventTimezone", TimeZone.getDefault().id); put("eventLocation", fixture.location)
            }
            val prior = existing.singleOrNull { it.marker == marker }
            operations += if (prior == null) ContentProviderOperation.newInsert(syncUri(CalendarContract.Events.CONTENT_URI))
                .withValues(values).build()
            else ContentProviderOperation.newUpdate(syncUri(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, prior.id)))
                .withValues(values).withExpectedCount(1).build()
        }
        // Atomic replacement prevents a failed setup from leaving half a scenario installed.
        if (operations.isNotEmpty()) context.contentResolver.applyBatch(CalendarContract.AUTHORITY, operations)
        val result = events(calendarId).filter { it.marker in wanted }
        check(result.size == fixtures.size)
        return fixtures.map { fixture -> result.single { it.marker == prefix + fixture.key } }
    }
}
