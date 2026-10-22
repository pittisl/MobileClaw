package edu.pitt.isl.mobileclaw.notiftest.skills.calendar

import org.junit.Assert.*
import org.junit.Test

class CalendarTargetPolicyTest {
    private val primary = CalendarTargetPolicy.Calendar(2, "Primary account", true)
    private val selected = CalendarTargetPolicy.Calendar(3, "Demo calendar", true)

    @Test fun explicitSelectionWinsRegardlessOfProviderOrder() {
        for (calendars in listOf(listOf(primary, selected), listOf(selected, primary))) {
            val choice = CalendarTargetPolicy.choose("3", calendars) as CalendarTargetPolicy.Choice.Selected
            assertEquals(selected, choice.calendar)
        }
    }

    @Test fun allCalendarsNeverChoosesAnArbitraryWriteDestination() {
        for (calendars in listOf(emptyList(), listOf(primary), listOf(primary, selected))) {
            val choice = CalendarTargetPolicy.choose("all", calendars) as CalendarTargetPolicy.Choice.Unavailable
            assertTrue(choice.reason.contains("Choose one calendar"))
        }
    }

    @Test fun missingSelectionDoesNotFallBackToTheAvailablePrimary() {
        val choice = CalendarTargetPolicy.choose("3", listOf(primary)) as CalendarTargetPolicy.Choice.Unavailable
        assertTrue(choice.reason.contains("unavailable"))
    }

    @Test fun readOnlySelectionDoesNotFallBackToAWritableCalendar() {
        val choice = CalendarTargetPolicy.choose("3", listOf(primary, selected.copy(writable = false)))
            as CalendarTargetPolicy.Choice.Unavailable
        assertTrue(choice.reason.contains("read-only"))
    }

    @Test fun invalidStoredSelectionNeverBroadensScope() {
        for (source in listOf("", "invalid", "0", "-3", "3 OR 1=1", "9223372036854775808")) {
            assertNull(CalendarTargetPolicy.selectedId(source))
            assertTrue(CalendarTargetPolicy.choose(source, listOf(primary, selected))
                is CalendarTargetPolicy.Choice.Unavailable)
        }
    }
}
