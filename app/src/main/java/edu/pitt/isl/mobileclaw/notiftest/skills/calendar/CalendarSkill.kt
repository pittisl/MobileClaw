package edu.pitt.isl.mobileclaw.notiftest.skills.calendar

import android.content.Context
import edu.pitt.isl.mobileclaw.notiftest.Capability
import edu.pitt.isl.mobileclaw.notiftest.Skill
import edu.pitt.isl.mobileclaw.notiftest.Tool

/** Reading upcoming events and adding new ones (the latter behind a confirmation). */
class CalendarSkill : Skill {
    override val id = "calendar"
    override val displayName = "Calendar"
    override val summary =
        "Read your selected calendar and add events to the same calendar after you confirm."
    override val capabilities = listOf(Capability.READ_CALENDAR, Capability.WRITE_CALENDAR)

    override fun tools(context: Context): List<Tool> = listOf(
        ReadCalendarTool(context),
        CreateEventTool(context),
    )
}
