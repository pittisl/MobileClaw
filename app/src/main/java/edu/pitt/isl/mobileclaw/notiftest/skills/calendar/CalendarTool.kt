package edu.pitt.isl.mobileclaw.notiftest.skills.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import edu.pitt.isl.mobileclaw.notiftest.ActionLogStore
import edu.pitt.isl.mobileclaw.notiftest.AppSettings
import edu.pitt.isl.mobileclaw.notiftest.Capability
import edu.pitt.isl.mobileclaw.notiftest.PendingAction
import edu.pitt.isl.mobileclaw.notiftest.PermissionSignal
import edu.pitt.isl.mobileclaw.notiftest.ReasoningModule
import edu.pitt.isl.mobileclaw.notiftest.Tool
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Shared date parsing/formatting for the calendar tools. */
private object CalTime {
    // Accepts ISO-8601 local ("2026-07-12T14:30"), with seconds, or a plain date.
    private val PATTERNS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm", "yyyy-MM-dd"
    )

    fun parse(text: String): Long? {
        text.toLongOrNull()?.let { return it } // already epoch millis
        for (p in PATTERNS) {
            try {
                return SimpleDateFormat(p, Locale.US).apply {
                    isLenient = false
                }.parse(text)?.time
            } catch (_: Exception) { /* try next */ }
        }
        return null
    }

    fun format(ms: Long): String =
        SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.US).format(Date(ms))
}

/** Query only the chosen calendar, including when another account has a primary calendar. */
private fun selectedCalendar(context: Context, id: Long): CalendarTargetPolicy.Calendar? =
    context.contentResolver.query(
        ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, id),
        arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL),
        null, null, null
    )?.use { cursor ->
        if (!cursor.moveToFirst()) null else CalendarTargetPolicy.Calendar(
            cursor.getLong(0), cursor.getString(1)?.takeIf { it.isNotBlank() } ?: "Unnamed calendar",
            cursor.getInt(2) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
        )
    }

/**
 * Reads calendar events in a time range. Read-only and ungated; output
 * is redacted before it can reach the cloud model. Requires READ_CALENDAR.
 */
class ReadCalendarTool(private val context: Context) : Tool {

    override val name = "read_calendar"
    override val description =
        "List events from the calendar selected in Settings (or all calendars when selected). " +
        "Defaults to the next 24 hours if no range is given."
    override val parameters = JSONObject(
        """
        {
          "type": "object",
          "properties": {
            "range_start": { "type": "string", "description": "Start of range: ISO-8601 local (2026-07-12T00:00) or epoch millis. Default: now." },
            "range_end":   { "type": "string", "description": "End of range: ISO-8601 local or epoch millis. Default: 24h after start." }
          }
        }
        """.trimIndent()
    )

    override suspend fun invoke(args: JSONObject): String = withContext(Dispatchers.IO) {
        if (!Capability.READ_CALENDAR.isGranted(context)) {
            PermissionSignal.request(Capability.READ_CALENDAR)
            return@withContext Capability.unavailable(Capability.READ_CALENDAR)
        }

        val now = System.currentTimeMillis()
        val start = args.optString("range_start").takeIf { it.isNotBlank() }?.let(CalTime::parse) ?: now
        val end = args.optString("range_end").takeIf { it.isNotBlank() }?.let(CalTime::parse)
            ?: (start + 24 * 60 * 60 * 1000L)

        // Query Instances (expands recurring events) over [start, end].
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, start)
            ContentUris.appendId(this, end)
        }.build()

        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY
        )

        val rows = mutableListOf<String>()
        val source = AppSettings.calendarReadSource
        // A missing or malformed selection must never widen into all calendars.
        val selectedId = CalendarTargetPolicy.selectedId(source)
        if (source != "all" && (selectedId == null || selectedCalendar(context, selectedId) == null)) {
            return@withContext "Selected calendar is unavailable. Choose a calendar in Settings; no events were read."
        }
        context.contentResolver.query(
            uri, projection,
            if (source == "all") null else "${CalendarContract.Instances.CALENDAR_ID} = ?",
            if (source == "all") null else arrayOf(selectedId.toString()),
            "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { c ->
            val titleIdx = c.getColumnIndex(CalendarContract.Instances.TITLE)
            val beginIdx = c.getColumnIndex(CalendarContract.Instances.BEGIN)
            val endIdx = c.getColumnIndex(CalendarContract.Instances.END)
            val locIdx = c.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
            while (c.moveToNext() && rows.size < 25) {
                val title = c.getString(titleIdx)?.ifBlank { "(no title)" } ?: "(no title)"
                val begin = c.getLong(beginIdx)
                val finish = c.getLong(endIdx)
                val loc = c.getString(locIdx)?.takeIf { it.isNotBlank() }
                rows.add(buildString {
                    append("${CalTime.format(begin)} – ${CalTime.format(finish)} — ${ReasoningModule.redact(title)}")
                    if (loc != null) append(" @ ${ReasoningModule.redact(loc)}")
                })
            }
        }

        if (rows.isEmpty()) "No events between ${CalTime.format(start)} and ${CalTime.format(end)}."
        else "Events:\n" + rows.joinToString("\n")
    }
}

/**
 * Creates a calendar event. Confirm-gated — the event is staged through
 * [PendingAction] so the user approves before it's written (and, for a
 * cloud-synced calendar, propagates). Requires WRITE_CALENDAR (and READ_CALENDAR
 * to find a target calendar).
 */
class CreateEventTool(private val context: Context) : Tool {

    override val name = "create_event"
    override val description =
        "Create an event in the same calendar selected for reading in Settings. " +
        "A single writable calendar must be selected; All calendars is read-only. " +
        "The user is asked to confirm the event and destination calendar before it is added. " +
        "Times are ISO-8601 local (2026-07-12T14:30) or epoch millis."
    override val parameters = JSONObject(
        """
        {
          "type": "object",
          "properties": {
            "title":       { "type": "string", "description": "Event title." },
            "start_time":  { "type": "string", "description": "Start: ISO-8601 local or epoch millis." },
            "end_time":    { "type": "string", "description": "End: ISO-8601 local or epoch millis. Defaults to 1 hour after start." },
            "location":    { "type": "string", "description": "Optional location." },
            "description": { "type": "string", "description": "Optional notes." }
          },
          "required": ["title", "start_time"]
        }
        """.trimIndent()
    )

    override suspend fun invoke(args: JSONObject): String = withContext(Dispatchers.IO) {
        val title = args.optString("title").trim()
        if (title.isBlank()) return@withContext "I need an event title."
        val start = args.optString("start_time").takeIf { it.isNotBlank() }?.let(CalTime::parse)
            ?: return@withContext "I couldn't understand the start time. Use ISO-8601 like 2026-07-12T14:30."
        val end = args.optString("end_time").takeIf { it.isNotBlank() }?.let(CalTime::parse)
            ?: (start + 60 * 60 * 1000L)
        val location = args.optString("location").takeIf { it.isNotBlank() }
        val description = args.optString("description").takeIf { it.isNotBlank() }

        for (capability in listOf(Capability.READ_CALENDAR, Capability.WRITE_CALENDAR)) {
            if (!capability.isGranted(context)) {
                PermissionSignal.request(capability)
                return@withContext Capability.unavailable(capability)
            }
        }

        val selection = try {
            currentTarget()
        } catch (_: SecurityException) {
            return@withContext "Calendar access is unavailable. Grant calendar access and try again; no event was added."
        }
        val calendar = when (selection) {
            is CalendarTargetPolicy.Choice.Selected -> selection.calendar
            is CalendarTargetPolicy.Choice.Unavailable -> return@withContext selection.reason
        }
        val calendarLabel = ReasoningModule.redact(calendar.name)

        val label = buildString {
            append("Add event: \"${ReasoningModule.redact(title)}\"\n${CalTime.format(start)} – ${CalTime.format(end)}")
            if (location != null) append("\n@ ${ReasoningModule.redact(location)}")
            append("\nCalendar: $calendarLabel")
        }

        val proposed = PendingAction.propose(
            PendingAction.Proposal(
                description = label,
                execute = { doCreate(calendar.id, title, start, end, location, description) }
            )
        )
        if (proposed) "Prepared a calendar event \"$title\" in $calendarLabel — the user needs to confirm before it's added."
        else "Could not show the calendar confirmation. No event was added; please try again."
    }

    private fun currentTarget(): CalendarTargetPolicy.Choice {
        val source = AppSettings.calendarReadSource
        val calendar = CalendarTargetPolicy.selectedId(source)?.let { selectedCalendar(context, it) }
        return CalendarTargetPolicy.choose(source, listOfNotNull(calendar))
    }

    private suspend fun doCreate(
        calendarId: Long, title: String, start: Long, end: Long,
        location: String?, description: String?
    ): String = withContext(Dispatchers.IO) {
        try {
            for (capability in listOf(Capability.READ_CALENDAR, Capability.WRITE_CALENDAR)) {
                if (!capability.isGranted(context)) return@withContext rejected(title,
                    "Calendar permission changed before confirmation. Grant calendar access and try again; no event was added.")
            }
            // A confirmation approves the displayed destination, not a different selection made later.
            if (CalendarTargetPolicy.selectedId(AppSettings.calendarReadSource) != calendarId) {
                return@withContext rejected(title,
                    "Selected calendar changed before confirmation. Please create the event again; no event was added.")
            }
            val target = when (val selection = currentTarget()) {
                is CalendarTargetPolicy.Choice.Selected -> selection.calendar
                is CalendarTargetPolicy.Choice.Unavailable -> return@withContext rejected(title, selection.reason)
            }
            if (target.id != calendarId) return@withContext rejected(title,
                "Selected calendar changed before confirmation. Please create the event again; no event was added.")
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, start)
                put(CalendarContract.Events.DTEND, end)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
                description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            val result = if (uri != null) "Event \"$title\" added for ${CalTime.format(start)} in ${ReasoningModule.redact(target.name)}."
                         else "Couldn't add the event (calendar rejected the insert)."
            ActionLogStore.record("create_event", title, "create", null, result, success = uri != null)
            result
        } catch (e: Exception) {
            val result = "Failed to add event: ${e.message}"
            ActionLogStore.record("create_event", title, "create", null, result, success = false)
            result
        }
    }

    private fun rejected(title: String, reason: String): String {
        ActionLogStore.record("create_event", title, "create", null, reason, success = false)
        return reason
    }
}
