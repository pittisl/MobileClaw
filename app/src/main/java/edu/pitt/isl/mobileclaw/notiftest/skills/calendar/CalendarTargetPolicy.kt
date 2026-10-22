package edu.pitt.isl.mobileclaw.notiftest.skills.calendar

/** One preference defines the read scope and write destination; never guess a primary calendar. */
internal object CalendarTargetPolicy {
    data class Calendar(val id: Long, val name: String, val writable: Boolean)

    sealed interface Choice {
        data class Selected(val calendar: Calendar) : Choice
        data class Unavailable(val reason: String) : Choice
    }

    fun selectedId(source: String): Long? = source.toLongOrNull()?.takeIf { it > 0 }

    fun choose(source: String, calendars: List<Calendar>): Choice {
        if (source == "all") return Choice.Unavailable(
            "Choose one calendar in Settings > Calendar before adding an event. All calendars is read-only."
        )
        val id = selectedId(source)
        val calendar = id?.let { selected -> calendars.firstOrNull { it.id == selected } }
            ?: return Choice.Unavailable(
                "Selected calendar is unavailable. Choose a calendar in Settings; no event was added."
            )
        if (!calendar.writable) return Choice.Unavailable(
            "Selected calendar is read-only. Choose a writable calendar in Settings; no event was added."
        )
        return Choice.Selected(calendar)
    }
}
